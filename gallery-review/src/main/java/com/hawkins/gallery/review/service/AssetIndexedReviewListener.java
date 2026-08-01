package com.hawkins.gallery.review.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hawkins.gallery.event.AssetIndexedEvent;
import com.hawkins.gallery.review.domain.JobType;

/** Automatically connects core indexing to the independent review queue. */
@Component
public class AssetIndexedReviewListener {
    private final ReviewQueueService reviewQueueService;

    public AssetIndexedReviewListener(ReviewQueueService reviewQueueService) {
        this.reviewQueueService = reviewQueueService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAssetIndexed(AssetIndexedEvent event) {
        reviewQueueService.enqueue(event.assetId(), JobType.NSFW, 40, false);
    }
}
