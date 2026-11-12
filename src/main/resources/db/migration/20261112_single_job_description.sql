-- Handles both historical split layouts and is safe to run again after consolidation.
DO $migration$
DECLARE
    legacy_third boolean;
    has_segments boolean;
BEGIN
    IF to_regclass('saved_jobs') IS NOT NULL THEN
        LOCK TABLE saved_jobs IN ACCESS EXCLUSIVE MODE;
        IF EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = 'saved_jobs'::regclass AND attname = 'job_description'
                AND atttypid <> 'text'::regtype AND NOT attisdropped
        ) THEN
            ALTER TABLE saved_jobs ALTER COLUMN job_description TYPE text;
        END IF;

        SELECT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = 'saved_jobs'::regclass AND attname = 'job_description3' AND NOT attisdropped
        ) INTO legacy_third;
        SELECT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = 'saved_jobs'::regclass
                AND attname IN ('job_description1', 'job_description2', 'job_description3') AND NOT attisdropped
        ) INTO has_segments;

        IF has_segments THEN
            -- JSON access permits missing historical columns without dynamic SQL.
            UPDATE saved_jobs AS job SET job_description = CASE
                WHEN legacy_third AND (
                    to_jsonb(job) ->> 'job_description1' IS NOT NULL
                    OR to_jsonb(job) ->> 'job_description2' IS NOT NULL
                    OR to_jsonb(job) ->> 'job_description3' IS NOT NULL
                ) THEN
                    COALESCE(to_jsonb(job) ->> 'job_description1', '')
                    || COALESCE(to_jsonb(job) ->> 'job_description2', '')
                    || COALESCE(to_jsonb(job) ->> 'job_description3', '')
                ELSE
                    COALESCE(job.job_description, '')
                    || COALESCE(to_jsonb(job) ->> 'job_description1', '')
                    || COALESCE(to_jsonb(job) ->> 'job_description2', '')
                END;

            ALTER TABLE saved_jobs
                DROP COLUMN IF EXISTS job_description1,
                DROP COLUMN IF EXISTS job_description2,
                DROP COLUMN IF EXISTS job_description3;
        END IF;
    END IF;
END;
$migration$;;
