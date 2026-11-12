-- Apply before deploying the worker when Hibernate schema updates are disabled.
ALTER TABLE saved_jobs ADD COLUMN IF NOT EXISTS comparison_request_id uuid;
ALTER TABLE saved_jobs ADD COLUMN IF NOT EXISTS comparison_lease_token uuid;
ALTER TABLE saved_jobs ADD COLUMN IF NOT EXISTS comparison_lease_until timestamptz;
ALTER TABLE saved_jobs ADD COLUMN IF NOT EXISTS comparison_attempts integer;
ALTER TABLE saved_jobs ADD COLUMN IF NOT EXISTS comparison_next_attempt_at timestamptz;
CREATE INDEX IF NOT EXISTS saved_jobs_comparison_queue_idx
    ON saved_jobs (comparison_status, comparison_next_attempt_at);
