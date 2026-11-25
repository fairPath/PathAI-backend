-- Runs before every integration test method (see IntegrationTestBase).
-- TRUNCATE (not a rolled-back transaction) because Analysis tests check
-- after-commit behaviour and compare-and-set across separate transactions,
-- which only see real commits.
TRUNCATE TABLE account_deletion, usage_record, analysis, saved_jobs, resume, users RESTART IDENTITY CASCADE;

-- The one-in-flight index: ddl-auto=update cannot create it. This is the same
-- statement the README's "Manual schema changes" gives for Supabase, so the
-- tests prove the exact SQL. IF NOT EXISTS because this runs before every test.
CREATE UNIQUE INDEX IF NOT EXISTS uq_analysis_one_in_flight_per_user
    ON analysis (user_id)
    WHERE status IN ('PENDING', 'PROCESSING');
