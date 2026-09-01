-- Correctness constraints and durable-worker metadata introduced by the
-- architecture remediation. This migration is additive and upgrades V1 databases.

-- Java Instant represents an absolute point in time. Preserve the existing values
-- as UTC while converting legacy timestamp-without-zone columns to timestamptz.
ALTER TABLE folders ALTER COLUMN created_at TYPE timestamp with time zone USING created_at AT TIME ZONE 'UTC';
ALTER TABLE assets ALTER COLUMN created_at TYPE timestamp with time zone USING created_at AT TIME ZONE 'UTC';
ALTER TABLE asset_metadata ALTER COLUMN ai_updated_at TYPE timestamp with time zone USING ai_updated_at AT TIME ZONE 'UTC';
ALTER TABLE asset_embeddings ALTER COLUMN created_at TYPE timestamp with time zone USING created_at AT TIME ZONE 'UTC';
ALTER TABLE known_persons ALTER COLUMN created_at TYPE timestamp with time zone USING created_at AT TIME ZONE 'UTC';
ALTER TABLE known_face_examples ALTER COLUMN created_at TYPE timestamp with time zone USING created_at AT TIME ZONE 'UTC';
ALTER TABLE face_detections ALTER COLUMN created_at TYPE timestamp with time zone USING created_at AT TIME ZONE 'UTC';

-- Database-enforced catalogue identity. Partial uniqueness permits manually
-- created folders, whose source_path is intentionally null.
CREATE UNIQUE INDEX uq_folders_source_path
    ON folders(source_path) WHERE source_path IS NOT NULL;
CREATE UNIQUE INDEX uq_assets_folder_checksum ON assets(folder_id, checksum);
CREATE UNIQUE INDEX uq_assets_folder_storage_path ON assets(folder_id, storage_path);

ALTER TABLE asset_metadata
    ADD COLUMN known_face_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_asset_metadata_ai_status CHECK (
        ai_status IS NULL OR ai_status IN ('PENDING','RETRY','PROCESSING','COMPLETE','FAILED','CANCELLED')
    ),
    ADD CONSTRAINT ck_asset_metadata_nsfw_level CHECK (
        nsfw_level IN ('SAFE','REVIEW','EXPLICIT','UNKNOWN')
    ),
    ADD CONSTRAINT ck_asset_metadata_nsfw_review_status CHECK (
        nsfw_review_status IN ('UNREVIEWED','KEPT','QUARANTINED','RESTORED','ERROR')
    );

ALTER TABLE asset_review
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_asset_review_nsfw_level CHECK (
        nsfw_level IN ('SAFE','REVIEW','EXPLICIT','UNKNOWN')
    ),
    ADD CONSTRAINT ck_asset_review_status CHECK (
        review_status IN ('PENDING','FLAGGED','KEPT','QUARANTINED','RESTORED','ERROR')
    );

ALTER TABLE processing_job
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN lease_until timestamp with time zone,
    ADD COLUMN worker_id varchar(128),
    ADD CONSTRAINT ck_processing_job_status CHECK (
        status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED')
    ),
    ADD CONSTRAINT ck_processing_job_type CHECK (
        job_type IN ('METADATA','THUMBNAIL','NSFW','VISION','FACE','EMBEDDING')
    );

CREATE INDEX idx_processing_job_expired_lease
    ON processing_job(lease_until) WHERE status = 'RUNNING';
