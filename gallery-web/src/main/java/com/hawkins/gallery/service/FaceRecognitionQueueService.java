package com.hawkins.gallery.service;

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

import com.hawkins.gallery.domain.AssetMetadata;
import com.hawkins.gallery.repository.AssetMetadataRepository;
import com.hawkins.gallery.review.domain.JobType;
import com.hawkins.gallery.review.domain.ProcessingJob;
import com.hawkins.gallery.review.service.ReviewQueueService;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FaceRecognitionQueueService {
    private final AssetService assets;
    private final AssetMetadataRepository metas;
    private final FaceDetectionService faceDetection;
    private final KnownFaceService knownFaces;
    private final ReviewQueueService queue;
    private final boolean enabled;
    private final int batchSize;
    private final ExecutorService executor;
    private final TransactionTemplate transactions;
    private final java.util.concurrent.ConcurrentMap<UUID, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

    public FaceRecognitionQueueService(
            AssetService assets,
            AssetMetadataRepository metas,
            FaceDetectionService faceDetection,
            KnownFaceService knownFaces,
            ReviewQueueService queue,
            PlatformTransactionManager transactionManager,
            @Value("${app.ai.face-recognition.enabled:true}") boolean enabled,
            @Value("${app.ai.face-recognition.batch-size:16}") int batchSize,
            @Value("${app.ai.face-recognition.worker-threads:4}") int workerThreads,
            @Value("${app.ai.face-recognition.executor-queue-capacity:64}") int queueCapacity) {
        this.assets = assets;
        this.metas = metas;
        this.faceDetection = faceDetection;
        this.knownFaces = knownFaces;
        this.queue = queue;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.transactions = new TransactionTemplate(transactionManager);

        AtomicInteger counter = new AtomicInteger();
        int threads = Math.max(1, workerThreads);
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), r -> {
            Thread t = new Thread(r, "face-recognition-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public QueueResult queueFolder(String folderId) {
        List<String> assetIds = assets.findByFolder(folderId).stream()
                .map(asset -> asset.getId())
                .toList();
        assetIds.forEach(assetId -> queue.enqueue(assetId, JobType.FACE, 30, true));
        return new QueueResult(assetIds.size(), "Queued face recognition for " + assetIds.size() + " image(s).");
    }

    @Scheduled(fixedDelayString = "${app.ai.face-recognition.fixed-delay-ms:250}")
    public void processBatch() {
        if (!enabled) return;
        int availableSlots = batchSize - inFlight.size();
        if (availableSlots <= 0) return;
        for (ProcessingJob job : queue.claimNextBatch(JobType.FACE, availableSlots)) {
            inFlight.computeIfAbsent(job.getId(), id -> CompletableFuture
                    .runAsync(() -> processJob(job), executor)
                    .whenComplete((result, error) -> inFlight.remove(id)));
        }
    }

    private void processJob(ProcessingJob job) {
        try {
            applyRecognition(job.getAssetId());
            queue.complete(job.getId(), job.getWorkerId());
        } catch (Exception ex) {
            log.warn("Face recognition failed for {}", job.getAssetId(), ex);
            queue.fail(job.getId(), job.getWorkerId(), ex);
        }
    }

    private void applyRecognition(String assetId) {
        long started = System.currentTimeMillis();
        var asset = assets.find(assetId).orElseThrow();
        var summary = faceDetection.detectAndRecognise(assetId, Path.of(asset.getStoragePath()));
        transactions.executeWithoutResult(status -> {
            AssetMetadata meta = metas.findById(assetId).orElse(null);
            if (meta == null) {
                return;
            }
            meta.setFaceCount(summary.totalFaces());
            meta.setFaceNames(knownFaces.mergeRecognisedNames(
                    meta.getFaceNames(), summary.recognisedNames(), List.of()));
            meta.setTimingFaceMs(System.currentTimeMillis() - started);
            meta.setAiUpdatedAt(Instant.now());
            metas.save(meta);
        });
        log.info("Face recognition job complete for asset {}: faces={}, recognised={}, duration={}ms",
                assetId, summary.totalFaces(), summary.matchedFaces(), System.currentTimeMillis() - started);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public record QueueResult(int queued, String message) {}
}
