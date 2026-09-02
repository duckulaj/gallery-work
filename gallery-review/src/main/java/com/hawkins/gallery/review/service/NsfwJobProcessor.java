package com.hawkins.gallery.review.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hawkins.gallery.domain.Asset;
import com.hawkins.gallery.repository.AssetRepository;
import com.hawkins.gallery.service.NsfwDetectionService;
import com.hawkins.gallery.review.domain.*;
import com.hawkins.gallery.review.repository.AssetReviewRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class NsfwJobProcessor implements NsfwDetectionService {
    private final ReviewQueueService queue;
    private final AssetRepository assets;
    private final AssetReviewRepository reviews;
    private final NsfwClient nsfw;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final int batchSize;
    private final TransactionTemplate transactions;
    private final ExecutorService executor;
    private final java.util.concurrent.ConcurrentMap<UUID, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

    public NsfwJobProcessor(ReviewQueueService queue, AssetRepository assets,
            AssetReviewRepository reviews, NsfwClient nsfw, ObjectMapper mapper,
            @Value("${app.ai.nsfw.enabled:true}") boolean enabled,
            @Value("${app.ai.nsfw.batch-size:16}") int batchSize,
            @Value("${app.ai.nsfw.worker-threads:4}") int workerThreads,
            @Value("${app.ai.nsfw.executor-queue-capacity:64}") int queueCapacity,
            PlatformTransactionManager transactionManager) {
        this.queue = queue; this.assets = assets; this.reviews = reviews;
        this.nsfw = nsfw; this.mapper = mapper; this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.transactions = new TransactionTemplate(transactionManager);
        AtomicInteger counter = new AtomicInteger();
        int threads = Math.max(1, workerThreads);
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), r -> {
            Thread t = new Thread(r, "nsfw-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Scheduled(fixedDelayString = "${app.ai.nsfw.fixed-delay-ms:250}")
    public void processBatch() {
        if (!enabled) return;
        int availableSlots = batchSize - inFlight.size();
        if (availableSlots <= 0) return;
        for (ProcessingJob job : queue.claimNextBatch(JobType.NSFW, availableSlots)) {
            inFlight.computeIfAbsent(job.getId(), id -> CompletableFuture
                    .runAsync(() -> processJob(job), executor)
                    .whenComplete((result, error) -> inFlight.remove(id)));
        }
    }

    private void processJob(ProcessingJob job) {
        try {
            analyseAsset(job.getAssetId());
            queue.complete(job.getId(), job.getWorkerId());
        } catch (Exception ex) {
            log.warn("NSFW analysis failed for {}", job.getAssetId(), ex);
            queue.fail(job.getId(), job.getWorkerId(), ex);
        }
    }

    @Override
    public void analyseAsset(String assetId) {
        long started = System.currentTimeMillis();
        try {
            Asset asset = assets.findById(assetId).orElseThrow();
            log.info("Invoking NSFW detection for asset {}", assetId);
            NsfwClient.Result result = nsfw.analyse(Path.of(asset.getStoragePath()));
            transactions.executeWithoutResult(status -> persist(asset, result));
            log.info("NSFW detection complete for asset {}: level={}, score={}, duration={}ms",
                    assetId, result.level(), result.score(), System.currentTimeMillis() - started);
        } catch (Exception ex) {
            transactions.executeWithoutResult(status -> markError(assetId, ex));
            throw ex instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("NSFW analysis failed for " + assetId, ex);
        }
    }

    @Override
    public boolean hasResult(String assetId) {
        return reviews.hasDetectorResult(assetId);
    }

    @Override
    public void queueAsset(String assetId) {
        queue.enqueueNsfw(List.of(assetId), false);
    }

    private void persist(Asset asset, NsfwClient.Result result) {
        AssetReview review = reviews.findById(asset.getId()).orElseGet(AssetReview::new);
        review.setAssetId(asset.getId());
        review.setOriginalPath(asset.getStoragePath());
        review.setNsfwScore(result.score());
        review.setNsfwLevel(parseLevel(result.level()));
        review.setDetectorVersion(result.scoringVersion());
        try {
            review.setDetectorLabelsJson(mapper.writeValueAsString(
                    result.labels() == null ? java.util.List.of() : result.labels()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize detector labels", ex);
        }
        review.setAnalysedAt(Instant.now());
        review.setErrorMessage(result.error());
        if (!review.isManualOverride()) {
            review.setReviewStatus(review.getNsfwLevel() == NsfwLevel.SAFE ? ReviewStatus.KEPT : ReviewStatus.FLAGGED);
        }
        reviews.save(review);
    }

    private void markError(String assetId, Exception ex) {
        AssetReview review = reviews.findById(assetId).orElseGet(AssetReview::new);
        review.setAssetId(assetId);
        review.setNsfwLevel(NsfwLevel.UNKNOWN);
        review.setReviewStatus(ReviewStatus.ERROR);
        review.setErrorMessage(ex.getMessage());
        reviews.save(review);
    }

    private NsfwLevel parseLevel(String value) {
        try { return NsfwLevel.valueOf(value == null ? "UNKNOWN" : value.toUpperCase()); }
        catch (IllegalArgumentException ex) { return NsfwLevel.UNKNOWN; }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
