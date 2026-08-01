package com.hawkins.gallery.review.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.hawkins.gallery.review.domain.*;

public interface AssetReviewRepository extends JpaRepository<AssetReview, String> {
    List<AssetReview> findByAssetIdIn(Collection<String> assetIds);
    long countByReviewStatus(ReviewStatus status);
    long countByNsfwLevel(NsfwLevel level);
}
