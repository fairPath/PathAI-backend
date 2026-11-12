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


## Background saved-job comparisons

Saving a job creates its immutable S3 resume snapshot and commits the saved job with
`comparison_status=PENDING`. The HTTP response returns at that point, without waiting
for the AI model. The frontend shows “Comparison in progress” and polls only pending
job IDs every two seconds using one authenticated batch request. Completed scores and
details update automatically in search results and saved jobs. Jobs without an active
resume remain `NOT_REQUESTED`, with the existing manual check after resume upload.

The durable queue lives in `saved_jobs` itself, so the save and queue entry are one
transaction. The worker runs inside Spring (one comparison at a time per instance),
claims rows using `FOR UPDATE SKIP LOCKED`, commits its 180-second lease, and calls
the AI service outside any database transaction with a 120-second timeout. No browser
connection or separate worker command is required. Multiple Spring instances may
consume the queue safely. Work is at-least-once: an expired lease may repeat inference,
but completion is fenced by saved-row ID, request ID, and claim token. Old results cannot
overwrite a newer comparison or recreate an unsaved job.

Set these in the backend `.env` or deployment environment:

```properties
FAIRPATH_AI_SERVICE_URL=http://localhost:8000
FAIRPATH_AI_INTERNAL_TOKEN=<same value as the AI service INTERNAL_TOKEN>
COMPARISON_WORKER_ENABLED=true
```

These variables used to be needed only in Next.js for comparison; Spring now needs them.
Missing worker credentials leave jobs pending and emit a startup warning. A stopped
backend resumes pending work on restart; an interrupted attempt becomes eligible after
its lease expires. Failed attempts retry after 10 and 20 seconds, up to three attempts;
the job then becomes `FAILED` and the UI offers manual retry. A retry creates a new
request ID. Saving an unchanged pending/completed comparison reuses it. Unsave deletes
the row (and queue entry) and attempts to remove its snapshot after commit.

With the existing `spring.jpa.hibernate.ddl-auto=update`, restarting Spring adds the
queue columns and index.
Deploy backend and frontend together: `/saved-jobs/prepare` now returns a saved-job DTO,
and `/saved-jobs/status?jobId=...` reads statuses restricted to the authenticated user.
The old browser-driven `/complete` and `/fail` endpoints are removed; workers persist
results internally. The AI service API is unchanged.

Snapshot copying still contributes to save latency; model inference no longer does.


### Background comparison tests

Run the worker and save-service unit tests with `./gradlew test`. The PostgreSQL queue
integration tests are enabled only with an explicit disposable test database:

```bash
FAIRPATH_TEST_DB_URL=jdbc:postgresql://localhost:5432/fairpath_test \
FAIRPATH_TEST_DB_USER=postgres FAIRPATH_TEST_DB_PASSWORD=<test-password> \
./gradlew test --tests '*PostgresJobComparisonQueueTests'
```

They create/drop a random test schema and exercise concurrent claims, restart recovery,
retry backoff, stale-result fencing, unsave/re-save, and final-attempt crashes. They never
call the AI service or S3. Do not point test configuration at the application database.


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
