package com.hawkins.gallery.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hawkins.gallery.domain.Asset;
import com.hawkins.gallery.repository.AssetRepository;
import com.hawkins.gallery.review.domain.AssetReview;
import com.hawkins.gallery.review.domain.ReviewStatus;
import com.hawkins.gallery.review.repository.AssetReviewRepository;

class ReviewServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void quarantineAndRestoreMoveTheAsset() throws Exception {
        Path original = tempDir.resolve("source/photo.jpg");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "photo");

        Asset asset = new Asset();
        asset.setId("asset-1");
        asset.setFilename(original.getFileName().toString());
        asset.setStoragePath(original.toString());

        AssetRepository assets = mock(AssetRepository.class);
        AssetReviewRepository reviews = mock(AssetReviewRepository.class);
        AtomicReference<AssetReview> savedReview = new AtomicReference<>();
        when(assets.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assets.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviews.findById(asset.getId())).thenAnswer(invocation -> Optional.ofNullable(savedReview.get()));
        when(reviews.save(any(AssetReview.class))).thenAnswer(invocation -> {
            AssetReview review = invocation.getArgument(0);
            savedReview.set(review);
            return review;
        });

        ReviewService service = new ReviewService(
                assets, reviews, mock(ReviewQueueService.class), tempDir.resolve("quarantine").toString());

        assertThat(service.quarantine(java.util.List.of(asset.getId()))).isEqualTo(1);
        AssetReview review = savedReview.get();
        Path quarantined = Path.of(review.getQuarantinePath());
        assertThat(original).doesNotExist();
        assertThat(quarantined).exists();
        assertThat(review.getReviewStatus()).isEqualTo(ReviewStatus.QUARANTINED);

        assertThat(service.restore(java.util.List.of(asset.getId()))).isEqualTo(1);
        assertThat(original).exists();
        assertThat(quarantined).doesNotExist();
        assertThat(review.getReviewStatus()).isEqualTo(ReviewStatus.RESTORED);
    }
}
