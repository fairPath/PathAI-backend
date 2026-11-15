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


## Explicit match checks with the latest resume

Saving only persists job metadata. `POST /saved-jobs/compare` accepts `{jobId}` and
queues a JWT-owner-scoped comparison; duplicate pending requests reuse the same ID.
Each attempt resolves the latest ACTIVE resume key and version. Completion checks
the resume ID, timestamp and key, discarding stale results after replacement.
Resume snapshots and S3 copy/cleanup code have been removed. The owner is resetting
the database; no migration or historical-data compatibility is included.
Completed results expose `comparisonResumeUpdatedAt` and `comparisonCompletedAt`.

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
