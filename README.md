## Local development

FairPath Backend – Local AWS & Resume Upload Setup

This project integrates with AWS S3 for resume storage.
Locally, developers authenticate using AWS CLI profiles.

This README explains how to set up your local environment on Windows or macOS/Linux.

Prerequisites

Java 17

Gradle (wrapper included)

AWS account access

AWS CLI installed

1. Install AWS CLI
   macOS
   brew install awscli

Windows

Download from:
https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html

Verify:

aws --version

2. Configure AWS CLI Profile (One-Time)

Each developer must configure their own AWS credentials locally.

aws configure --profile fairpath

Enter:

AWS Access Key ID

AWS Secret Access Key

Default region: us-east-2

Output format: json

This creates entries in:

~/.aws/credentials
~/.aws/config

3. Verify AWS Access (Required)

Run:

aws sts get-caller-identity --profile fairpath

Then verify S3 access:

aws s3 ls s3://fairpath-resumes-dev/resumes/ \
 --profile fairpath \
 --region us-east-2

If both commands succeed, AWS access is correctly configured.

4. Environment Configuration
   refer to .env.example -> create .env and fill out fields

   Resume Analysis calls the AI service (`fairPath-ai-service`), so `.env` also needs:
   - `FAIRPATH_AI_SERVICE_URL`, e.g. `http://localhost:8000`
   - `FAIRPATH_AI_INTERNAL_TOKEN`, which must equal `INTERNAL_TOKEN` in the AI service's environment

   Spring refuses to boot without them. These are the names the frontend used before analysis moved behind Spring; move the values from the frontend's env into this `.env`.

5. Running the Backend Locally

Option A (Recommended): Use provided scripts
Windows
.\scripts\run-dev.ps1

macOS / Linux
./scripts/run-dev.sh

These scripts:

export required AWS environment variables

start Spring via ./gradlew bootRun

Once the backend is running:

http://localhost:8080/ will be available

S3 Access test:
GET http://localhost:8080/api/aws/s3-test


## Saved-job match checks

Saving through `POST /saved-jobs/prepare` only persists the job. An explicit
`POST /saved-jobs/compare` with `{ jobId }` queues comparison and returns 202.
The JWT owner scopes every saved-job and active-resume lookup. Repeated clicks
while PENDING reuse the existing request; a later manual check creates a new request ID.

Both Analysis and comparison dispatch after commit through `AiRequestExecution`.
It dispatches to independent fixed-size `@Async` pools: eight Analysis threads
and eight comparison threads, never a scheduler thread. Configure their sizes with
`fairpath.ai-execution.analysis-threads` and `comparison-threads`. Each pool retains
the original queue behavior; requests beyond its worker capacity wait in its queue.
Features supply a request/success operation; the shared component owns execution
and Analysis's original one-retry policy.

Each comparison claim resolves the latest ACTIVE resume and its live S3 key.
Request, lease and resume-version guards reject stale results. A changed resume
ends this request with `RESUME_CHANGED`; the user can request a new comparison.
The same request is reused for the single connection-level retry.

Both calls automatically retry only a refused connection or HTTP 503 without a
valid envelope, once after `fairpath.ai-service.retry-delay` (default 5 seconds).
Timeouts, typed provider errors, other statuses and result-storage failures do not
automatically replay inference. Manual retryability follows Analysis's original
stored-code policy: only `TEXT_NOT_EXTRACTABLE` and `FILE_UNREADABLE` are permanent.
Feature preconditions such as a missing active resume remain explicit errors.
User-session authentication and internal AI authentication remain separate.

`aiServiceRestClient` / `AiServiceErrors` share bounded safe error decoding and
outcome mapping. Valid `Retry-After` metadata is still decoded, but this restored
Analysis policy uses its original fixed delay and does not automatically retry
typed provider rate limits. The AI gateway still sets `store=False`.

The former comparison scheduler and durable three-attempt backoff are removed.
Like Analysis on current main, events interrupted by a restart are not replayed;
stale-work recovery remains follow-up work. Comparison lease guards default to
300 seconds to cover two 120-second calls plus the retry wait. Expired claims are
failed when the comparison claim path next runs; no background inference replay
is scheduled. The DTO continues exposing status, error/retryability and version fields.

The UI polls pending jobs every two seconds. Permanent failures disable Check match
for that page session, with fixed error copy; refresh permits another deliberate attempt.
Only a user-session 401 redirects to login. Internal AI token failures use the
original Analysis INTERNAL_ERROR/manual-retry behavior and never redirect users.

Required backend environment: `FAIRPATH_AI_SERVICE_URL` and
`FAIRPATH_AI_INTERNAL_TOKEN` (matching AI `INTERNAL_TOKEN`), plus existing DB/JWT/AWS
settings. The AI bucket must match the active resume bucket.
`COMPARISON_WORKER_ENABLED` controls worker enablement.

Deploy the frontend, backend and AI service changes together. Hibernate's existing
`ddl-auto=update` adds nullable error/retryability/version/completion columns on
human-run startup. No migration scripts are included; agents never start the app
against the shared database.

`./gradlew test` uses disposable Testcontainers PostgreSQL and WireMock.
Tests cover save-only behavior, explicit comparison, ownership, permanent failures,
concurrent claims, lease recovery and stale-resume result fencing.

## Saved-job description storage

The complete description is stored in one PostgreSQL `job_description TEXT` column.
The API returns it unchanged as `jobDescription`; the worker sends that same full text
to the AI service. There is no application truncation or splitting. Adzuna's public search
API supplies a snippet of up to 500 characters, which is preserved in full.

Development uses an empty database with Hibernate schema updates enabled. No data
migration is required. The UI consumes one `jobDescription` string, and the AI service
accepts the complete description without the old 756-character limit.

## Running tests

```
./gradlew test
```

Integration tests start a throwaway Postgres with Testcontainers, so a Docker daemon must be running (Docker Engine 25 or newer). They never read the local `.env` and never connect to Supabase.

Colima/OrbStack users: Testcontainers looks for `/var/run/docker.sock`. If your socket lives elsewhere, set it once for your machine in `~/.testcontainers.properties`, for example:

```
docker.host=unix:///Users/<you>/.orbstack/run/docker.sock
```

## Manual schema changes

`spring.jpa.hibernate.ddl-auto=update` creates new tables and nullable columns, but it cannot create partial indexes, widen enum CHECK constraints, or rename or drop anything. Those are run **by hand** in the Supabase SQL editor by a developer (never by an agent or a tool: `fairpath-rules` rule 7). The database is shared by dev and prod, so announce the change to the other developer first and log it here.

Integration tests apply the same statements to their throwaway Postgres (`src/test/resources/sql/reset.sql`), so the tests prove the exact SQL below.

### 2026-11: one Analysis in flight per user (ticket #60)

Allows at most one `PENDING` or `PROCESSING` Analysis per user. A second concurrent Analyze fails and maps to `ANALYSIS_IN_PROGRESS`.

1. Check for rows that would block the index. Nothing sweeps stuck Analyses yet (ticket #59), so a row left `PENDING` or `PROCESSING` by a restart can exist. This must return no rows:

   ```sql
   SELECT user_id, count(*)
   FROM analysis
   WHERE status IN ('PENDING', 'PROCESSING')
   GROUP BY user_id
   HAVING count(*) > 1;
   ```

   If it returns rows, fail the stale ones first (this is real data, so look before running):

   ```sql
   UPDATE analysis SET status = 'FAILED', error_code = 'ANALYSIS_TIMED_OUT'
   WHERE status IN ('PENDING', 'PROCESSING') AND created_at < now() - interval '10 minutes';
   ```

2. Create the index:

   ```sql
   CREATE UNIQUE INDEX IF NOT EXISTS uq_analysis_one_in_flight_per_user
       ON analysis (user_id)
       WHERE status IN ('PENDING', 'PROCESSING');
   ```

The index name is also a constant in the code (`Analysis.ONE_IN_FLIGHT_INDEX`). If you rename the index, rename the constant too.

Until ticket #59 lands, a Spring restart in the middle of an Analysis leaves a row `PENDING` or `PROCESSING`. That user then cannot Analyze, replace or delete their Resume until the row is failed. The `UPDATE` above clears it.
