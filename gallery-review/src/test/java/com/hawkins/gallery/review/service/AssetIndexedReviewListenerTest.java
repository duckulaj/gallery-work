package com.hawkins.gallery.review.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.hawkins.gallery.event.AssetIndexedEvent;
import com.hawkins.gallery.review.domain.JobType;

class AssetIndexedReviewListenerTest {

    private final ReviewQueueService queue = mock(ReviewQueueService.class);
    private final AssetIndexedReviewListener listener = new AssetIndexedReviewListener(queue);

    @Test
    void queuesNewAssetsWithoutReplacingCompletedResults() {
        listener.onAssetIndexed(new AssetIndexedEvent("asset-1"));

        verify(queue).enqueue("asset-1", JobType.NSFW, 40, false);
    }
}
