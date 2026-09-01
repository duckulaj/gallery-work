-- Album browsing and recursive folder traversal.
CREATE INDEX IF NOT EXISTS idx_folders_parent_name ON folders(parent_id, name);
CREATE INDEX IF NOT EXISTS idx_assets_folder_created ON assets(folder_id, created_at DESC);

-- Worker polling starts with job type and only needs runnable rows. Keeping
-- completed history out of these partial indexes prevents queue degradation.
CREATE INDEX IF NOT EXISTS idx_processing_job_pending_poll
    ON processing_job(job_type, priority, available_at, created_at)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_processing_job_running_lease
    ON processing_job(job_type, lease_until)
    WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_processing_job_completed_cleanup
    ON processing_job(completed_at)
    WHERE status = 'COMPLETED';
