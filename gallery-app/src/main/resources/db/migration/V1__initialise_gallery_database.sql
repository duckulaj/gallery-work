-- Gallery App clean database initialisation
-- PostgreSQL 15+ with pgvector installed on the database server.
-- This baseline represents the final schema after the modular application update.
-- It is intended for a NEW/EMPTY database, not an in-place upgrade of an existing one.

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- Folder and asset catalogue
-- ---------------------------------------------------------------------------

CREATE TABLE folders (
    id          varchar(36) PRIMARY KEY,
    name        varchar(255) NOT NULL,
    parent_id   varchar(36),
    source_path varchar(1024),
    created_at  timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_folder_parent
        FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE CASCADE
);

CREATE TABLE assets (
    id             varchar(36) PRIMARY KEY,
    folder_id      varchar(36) NOT NULL,
    filename       varchar(512) NOT NULL,
    content_type   varchar(100) NOT NULL,
    size_bytes     bigint NOT NULL,
    width          integer,
    height         integer,
    checksum       varchar(64) NOT NULL,
    storage_path   varchar(1024) NOT NULL,
    thumbnail_path varchar(1024),
    created_at     timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_asset_folder
        FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE
);

CREATE INDEX assets_folder_id_idx ON assets(folder_id);
CREATE INDEX ft_asset_filename
    ON assets USING gin(to_tsvector('english', filename));

-- ---------------------------------------------------------------------------
-- AI metadata and semantic-search embeddings
-- ---------------------------------------------------------------------------

CREATE TABLE asset_metadata (
    asset_id           varchar(36) PRIMARY KEY,
    title              varchar(255),
    description        text,
    ai_caption         text,
    ai_tags            text,
    exif_json          json,
    dominant_colors    varchar(512),
    face_count         integer,
    face_names         text,
    face_descriptions  text,
    scene_type         varchar(255),
    scene_labels       text,
    ai_status          varchar(24),
    ai_model           varchar(255),
    ai_error           text,
    ai_updated_at      timestamp,
    timing_exif_ms     bigint,
    timing_ai_ms       bigint,
    timing_face_ms     bigint,
    timing_embed_ms    bigint,
    timing_nsfw_ms     bigint,
    timing_total_ms    bigint,
    nsfw_score         double precision,
    nsfw_level         varchar(24) NOT NULL DEFAULT 'UNKNOWN',
    nsfw_labels        text,
    nsfw_review_status varchar(24) NOT NULL DEFAULT 'UNREVIEWED',
    nsfw_reviewed_at   timestamp with time zone,
    CONSTRAINT fk_meta_asset
        FOREIGN KEY (asset_id) REFERENCES assets(id) ON DELETE CASCADE
);

CREATE INDEX ft_meta_text ON asset_metadata USING gin(
    to_tsvector(
        'english',
        coalesce(title, '') || ' ' ||
        coalesce(description, '') || ' ' ||
        coalesce(ai_caption, '') || ' ' ||
        coalesce(ai_tags, '') || ' ' ||
        coalesce(dominant_colors, '') || ' ' ||
        coalesce(face_names, '') || ' ' ||
        coalesce(face_descriptions, '') || ' ' ||
        coalesce(scene_type, '') || ' ' ||
        coalesce(scene_labels, '')
    )
);

CREATE INDEX idx_asset_metadata_ai_queue
    ON asset_metadata(ai_status, ai_updated_at);

CREATE INDEX idx_asset_metadata_nsfw_review
    ON asset_metadata(nsfw_review_status, nsfw_score DESC);

CREATE TABLE asset_embeddings (
    asset_id       varchar(36) PRIMARY KEY,
    model          varchar(128) NOT NULL,
    dimensions     integer NOT NULL DEFAULT 1024,
    embedding_json json NOT NULL DEFAULT '[]'::json,
    embedding      vector(1024),
    created_at     timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_embedding_asset
        FOREIGN KEY (asset_id) REFERENCES assets(id) ON DELETE CASCADE,
    CONSTRAINT ck_asset_embeddings_dimensions
        CHECK (dimensions = 1024)
);

CREATE INDEX asset_embeddings_embedding_hnsw_idx
    ON asset_embeddings USING hnsw (embedding vector_cosine_ops);

-- ---------------------------------------------------------------------------
-- Known people and face-recognition data
-- ---------------------------------------------------------------------------

CREATE TABLE known_persons (
    id           varchar(36) PRIMARY KEY,
    display_name varchar(255) NOT NULL UNIQUE,
    created_at   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE known_face_examples (
    id               varchar(36) PRIMARY KEY,
    person_id        varchar(36) NOT NULL,
    source_asset_id  varchar(36),
    face_description text,
    embedding        vector(512),
    created_at       timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_known_face_person
        FOREIGN KEY (person_id) REFERENCES known_persons(id) ON DELETE CASCADE,
    CONSTRAINT fk_known_face_asset
        FOREIGN KEY (source_asset_id) REFERENCES assets(id) ON DELETE SET NULL
);

CREATE INDEX idx_known_face_person ON known_face_examples(person_id);
CREATE INDEX idx_known_face_asset ON known_face_examples(source_asset_id);
CREATE INDEX known_face_emb_hnsw_idx
    ON known_face_examples USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

CREATE TABLE face_detections (
    id             varchar(36) PRIMARY KEY,
    asset_id       varchar(36) NOT NULL,
    bbox_json      text NOT NULL,
    embedding_json text,
    person_id      varchar(36),
    person_name    varchar(255),
    confidence     real,
    crop_path      varchar(1024),
    created_at     timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_fd_asset
        FOREIGN KEY (asset_id) REFERENCES assets(id) ON DELETE CASCADE,
    CONSTRAINT fk_fd_person
        FOREIGN KEY (person_id) REFERENCES known_persons(id) ON DELETE SET NULL
);

CREATE INDEX idx_face_det_asset ON face_detections(asset_id);
CREATE INDEX idx_face_det_person ON face_detections(person_id);

-- ---------------------------------------------------------------------------
-- Independent NSFW review workspace and processing queue
-- ---------------------------------------------------------------------------

CREATE TABLE asset_review (
    asset_id             varchar(36) PRIMARY KEY,
    nsfw_score           double precision,
    nsfw_level           varchar(24) NOT NULL DEFAULT 'UNKNOWN',
    detector_version     integer,
    detector_labels_json text NOT NULL DEFAULT '[]',
    review_status        varchar(24) NOT NULL DEFAULT 'PENDING',
    manual_override      boolean NOT NULL DEFAULT false,
    original_path        text,
    quarantine_path      text,
    error_message        text,
    analysed_at          timestamp with time zone,
    reviewed_at          timestamp with time zone,
    updated_at           timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT fk_asset_review_asset
        FOREIGN KEY (asset_id) REFERENCES assets(id) ON DELETE CASCADE
);

CREATE INDEX idx_asset_review_level ON asset_review(nsfw_level);
CREATE INDEX idx_asset_review_status ON asset_review(review_status);
CREATE INDEX idx_asset_review_score ON asset_review(nsfw_score DESC NULLS LAST);

CREATE TABLE processing_job (
    id           uuid PRIMARY KEY,
    asset_id     varchar(36) NOT NULL,
    job_type     varchar(32) NOT NULL,
    status       varchar(24) NOT NULL,
    priority     integer NOT NULL DEFAULT 100,
    attempts     integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3,
    last_error   text,
    available_at timestamp with time zone NOT NULL DEFAULT now(),
    started_at   timestamp with time zone,
    completed_at timestamp with time zone,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT fk_processing_job_asset
        FOREIGN KEY (asset_id) REFERENCES assets(id) ON DELETE CASCADE,
    CONSTRAINT uq_processing_job_asset_type UNIQUE(asset_id, job_type),
    CONSTRAINT ck_processing_job_attempts
        CHECK (attempts >= 0 AND max_attempts > 0)
);

CREATE INDEX idx_processing_job_queue
    ON processing_job(status, priority, available_at, created_at);
