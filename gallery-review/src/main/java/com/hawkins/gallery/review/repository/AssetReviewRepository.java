package com.hawkins.gallery.review.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import com.hawkins.gallery.review.domain.*;

public interface AssetReviewRepository extends JpaRepository<AssetReview, String> {
    java.util.List<AssetReview> findByOperationStatusIn(java.util.Collection<FileOperationStatus> statuses);
    List<AssetReview> findByAssetIdIn(Collection<String> assetIds);
    long countByReviewStatus(ReviewStatus status);
    long countByNsfwLevel(NsfwLevel level);

    @Query(value = """
        select a.id, a.filename, a.storage_path as path, a.thumbnail_path,
               r.nsfw_score as score, coalesce(r.nsfw_level, 'UNKNOWN') as level,
               coalesce(r.review_status, 'PENDING') as status,
               coalesce(r.manual_override, false) as overridden, r.error_message as error
          from assets a left join asset_review r on r.asset_id = a.id
         where (:q = '' or lower(a.filename) like '%' || :q || '%'
                         or lower(a.storage_path) like '%' || :q || '%')
           and (:filter = 'ALL'
             or (:filter = 'FLAGGED' and (r.nsfw_score >= :threshold or r.review_status = 'FLAGGED'))
             or coalesce(r.review_status, 'PENDING') = :filter)
         order by r.nsfw_score desc nulls last, a.created_at desc
        """, nativeQuery = true)
    List<ReviewCardRow> findCards(@Param("filter") String filter, @Param("threshold") double threshold,
            @Param("q") String query, Pageable pageable);

    @Query(value = "select count(*) from asset_review where nsfw_score >= :threshold or review_status = 'FLAGGED'",
            nativeQuery = true)
    long countFlagged(@Param("threshold") double threshold);

    interface ReviewCardRow {
        String getId();
        String getFilename();
        String getPath();
        String getThumbnailPath();
        Double getScore();
        String getLevel();
        String getStatus();
        boolean getOverridden();
        String getError();
    }
}
