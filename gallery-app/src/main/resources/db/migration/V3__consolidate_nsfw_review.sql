-- Preserve any legacy enrichment-side NSFW result that does not already have a
-- canonical asset_review row, then remove the duplicated metadata representation.
INSERT INTO asset_review (
    asset_id, nsfw_score, nsfw_level, detector_labels_json, review_status,
    manual_override, analysed_at, updated_at, version
)
SELECT asset_id, nsfw_score, nsfw_level, coalesce(nsfw_labels, '[]'),
       CASE nsfw_review_status
           WHEN 'KEPT' THEN 'KEPT'
           WHEN 'QUARANTINED' THEN 'QUARANTINED'
           WHEN 'RESTORED' THEN 'RESTORED'
           WHEN 'ERROR' THEN 'ERROR'
           ELSE CASE WHEN nsfw_level IN ('REVIEW','EXPLICIT') THEN 'FLAGGED' ELSE 'PENDING' END
       END,
       nsfw_review_status <> 'UNREVIEWED', nsfw_reviewed_at, now(), 0
  FROM asset_metadata
 WHERE nsfw_score IS NOT NULL
ON CONFLICT (asset_id) DO NOTHING;

DROP INDEX IF EXISTS idx_asset_metadata_nsfw_review;
ALTER TABLE asset_metadata
    DROP CONSTRAINT IF EXISTS ck_asset_metadata_nsfw_level,
    DROP CONSTRAINT IF EXISTS ck_asset_metadata_nsfw_review_status,
    DROP COLUMN nsfw_score,
    DROP COLUMN nsfw_level,
    DROP COLUMN nsfw_labels,
    DROP COLUMN nsfw_review_status,
    DROP COLUMN nsfw_reviewed_at,
    DROP COLUMN timing_nsfw_ms;
