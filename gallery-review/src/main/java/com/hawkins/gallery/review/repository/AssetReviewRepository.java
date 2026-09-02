package com.hawkins.gallery.review.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;
import com.hawkins.gallery.review.domain.*;

public interface AssetReviewRepository extends JpaRepository<AssetReview, String> {
    java.util.List<AssetReview> findByOperationStatusIn(java.util.Collection<FileOperationStatus> statuses);
    List<AssetReview> findByAssetIdIn(Collection<String> assetIds);
    long countByReviewStatus(ReviewStatus status);
    long countByNsfwLevel(NsfwLevel level);

    @Query(value = """
        select exists (
            select 1
              from asset_review
             where asset_id = :assetId
               and analysed_at is not null
               and nsfw_level <> 'UNKNOWN'
               and (error_message is null or error_message = '')
        )
        """, nativeQuery = true)
    boolean hasDetectorResult(@Param("assetId") String assetId);

    @Modifying
    @Query(value = """
        insert into asset_review(asset_id, review_status, manual_override, reviewed_at, updated_at,
                                 detector_labels_json, nsfw_level, operation_status, version)
        select a.id, :status, true, now(), now(), '[]', 'UNKNOWN', 'NONE', 0
          from assets a where a.id in (:ids)
        on conflict (asset_id) do update set review_status=excluded.review_status,
          manual_override=true, reviewed_at=now(), updated_at=now(), version=asset_review.version+1
        """, nativeQuery = true)
    int bulkSetStatus(@Param("ids") Collection<String> ids, @Param("status") String status);

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
    Slice<ReviewCardRow> findCards(@Param("filter") String filter, @Param("threshold") double threshold,
            @Param("q") String query, Pageable pageable);

    @Query(value = "select count(*) from asset_review where nsfw_score >= :threshold or review_status = 'FLAGGED'",
            nativeQuery = true)
    long countFlagged(@Param("threshold") double threshold);

    @Query(value = """
        select (select count(*) from assets) as total,
               count(*) filter (where nsfw_score >= :threshold or review_status='FLAGGED') as flagged,
               count(*) filter (where review_status='KEPT') as kept,
               count(*) filter (where review_status='QUARANTINED') as quarantined,
               (select count(*) from assets) - count(*) + count(*) filter (where review_status='PENDING') as pending,
               count(*) filter (where review_status='ERROR') as errors
          from asset_review
        """, nativeQuery = true)
    ReviewCountsRow aggregateCounts(@Param("threshold") double threshold);

    interface ReviewCountsRow {
        long getTotal(); long getFlagged(); long getKept(); long getQuarantined();
        long getPending(); long getErrors();
    }

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
