CREATE TABLE IF NOT EXISTS import_failures (
    source_path      varchar(1024) PRIMARY KEY,
    size_bytes       bigint NOT NULL,
    last_modified_at timestamp with time zone NOT NULL,
    reason           varchar(255) NOT NULL,
    detail           text,
    failed_at        timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_import_failures_fingerprint
    ON import_failures(source_path, size_bytes, last_modified_at);
