package com.hawkins.gallery.review.service;

import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hawkins.gallery.domain.Asset;
import com.hawkins.gallery.repository.AssetRepository;
import com.hawkins.gallery.review.domain.*;
import com.hawkins.gallery.review.repository.AssetReviewRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class NsfwJobProcessor {
    private final ReviewQueueService queue;
    private final AssetRepository assets;
    private final AssetReviewRepository reviews;
    private final NsfwClient nsfw;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final TransactionTemplate transactions;

    public NsfwJobProcessor(ReviewQueueService queue, AssetRepository assets,
            AssetReviewRepository reviews, NsfwClient nsfw, ObjectMapper mapper,
            @Value("${app.ai.nsfw.enabled:true}") boolean enabled,
            PlatformTransactionManager transactionManager) {
        this.queue = queue; this.assets = assets; this.reviews = reviews;
        this.nsfw = nsfw; this.mapper = mapper; this.enabled = enabled;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.ai.nsfw.fixed-delay-ms:1200}")
    public void processOne() {
        if (!enabled) return;
        queue.claimNext(JobType.NSFW).ifPresent(job -> {
            try {
                Asset asset = assets.findById(job.getAssetId()).orElseThrow();
                NsfwClient.Result result = nsfw.analyse(Path.of(asset.getStoragePath()));
                transactions.executeWithoutResult(status -> persist(asset, result));
                queue.complete(job.getId(), job.getWorkerId());
            } catch (Exception ex) {
                log.warn("NSFW analysis failed for {}", job.getAssetId(), ex);
                transactions.executeWithoutResult(status -> markError(job.getAssetId(), ex));
                queue.fail(job.getId(), job.getWorkerId(), ex);
            }
        });
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
}
