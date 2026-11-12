-- Runs before every integration test method (see IntegrationTestBase).
-- TRUNCATE (not a rolled-back transaction) because later tickets test
-- after-commit behaviour and compare-and-set across separate transactions,
-- which only see real commits.
TRUNCATE TABLE saved_jobs, resume, users RESTART IDENTITY CASCADE;
