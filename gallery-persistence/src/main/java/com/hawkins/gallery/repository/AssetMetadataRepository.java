package com.hawkins.gallery.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hawkins.gallery.domain.AssetMetadata;
import com.hawkins.gallery.domain.AiStatus;

public interface AssetMetadataRepository extends JpaRepository<AssetMetadata, String> {
    /**
     * Picks the next asset IDs eligible for AI enrichment, up to the current
     * scheduler capacity.
     * Written as UNION ALL (one branch per status value) so the planner can
     * use a targeted index scan on idx_asset_metadata_ai_queue for each branch
     * instead of a sequential scan caused by an OR predicate.
     */
    @Query(value = """
            SELECT asset_id FROM asset_metadata WHERE ai_status = 'PENDING'
            UNION ALL
            SELECT asset_id FROM asset_metadata WHERE ai_status = 'RETRY'
            UNION ALL
            SELECT asset_id FROM asset_metadata
             WHERE ai_status = 'PROCESSING'
               AND ai_updated_at < NOW() - INTERVAL '30 minutes'
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findNextAiQueueIds(@Param("limit") int limit);

    /** Atomically claims an eligible row so concurrent schedulers cannot process it twice. */
    @Modifying
    @Query(value = """
            UPDATE asset_metadata
               SET ai_status = 'PROCESSING', ai_error = NULL, ai_updated_at = NOW()
             WHERE asset_id = :assetId
               AND (ai_status IN ('PENDING', 'RETRY')
                    OR (ai_status = 'PROCESSING'
                        AND ai_updated_at < NOW() - INTERVAL '30 minutes'))
            """, nativeQuery = true)
    int claimForAi(@Param("assetId") String assetId);

    long countByAiStatus(AiStatus aiStatus);

    java.util.List<com.hawkins.gallery.domain.AssetMetadata> findByAiStatusIn(java.util.List<AiStatus> statuses);

    /**
     * Resets rows interrupted by a JVM shutdown: PROCESSING → FAILED.
     * Called once on startup so stale in-flight jobs don't silently disappear.
     */
    @Modifying
    @Query(value = "UPDATE asset_metadata SET ai_status = 'FAILED', ai_error = 'Interrupted by application restart' WHERE ai_status = 'PROCESSING'", nativeQuery = true)
    int resetInterruptedProcessing();

    @Modifying
    @Query(value = """
            UPDATE asset_metadata m SET ai_status = 'PENDING', ai_error = NULL, ai_updated_at = NOW()
             FROM assets a
             WHERE a.id = m.asset_id AND a.folder_id = :folderId
               AND (:force OR m.ai_status IS NULL OR m.ai_status IN ('CANCELLED','FAILED'))
            """, nativeQuery = true)
    int queueFolder(@Param("folderId") String folderId, @Param("force") boolean force);

    @Modifying
    @Query(value = """
            UPDATE asset_metadata SET ai_status = 'PENDING', ai_error = NULL, ai_updated_at = NOW()
             WHERE ai_status IS NULL OR ai_status IN ('CANCELLED','FAILED')
            """, nativeQuery = true)
    int queueMissingGlobal();

    @Modifying
    @Query(value = "UPDATE asset_metadata SET ai_status='PENDING', ai_error=NULL, ai_updated_at=NOW()",
            nativeQuery = true)
    int queueAllGlobal();

    @Modifying
    @Query(value = """
            UPDATE asset_metadata SET ai_status = 'CANCELLED', ai_error = 'Cancelled by user', ai_updated_at = NOW()
             WHERE ai_status IN ('PENDING','RETRY','PROCESSING')
            """, nativeQuery = true)
    int cancelActive();
}
