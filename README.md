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

## Running tests

```
./gradlew test
```

Integration tests start a throwaway Postgres with Testcontainers, so a Docker daemon must be running (Docker Engine 25 or newer). They never read the local `.env` and never connect to Supabase.

Colima/OrbStack users: Testcontainers looks for `/var/run/docker.sock`. If your socket lives elsewhere, set it once for your machine in `~/.testcontainers.properties`, for example:

```
docker.host=unix:///Users/<you>/.orbstack/run/docker.sock
```
