ALTER TABLE asset_review
    ADD COLUMN operation_status varchar(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN operation_error text,
    ADD CONSTRAINT ck_asset_review_operation_status CHECK (
        operation_status IN ('NONE','MOVE_PENDING','MOVED','MOVE_FAILED','RESTORE_PENDING','RESTORE_FAILED')
    );

CREATE INDEX idx_asset_review_pending_file_operation
    ON asset_review(operation_status)
    WHERE operation_status <> 'NONE' AND operation_status <> 'MOVED';
