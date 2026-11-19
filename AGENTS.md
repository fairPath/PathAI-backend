# Agent instructions

## Rules

Read `RULES.md` from `fairPath/fairpath-rules` before starting any ticket or implementation. Every change in this repo follows it.

- With the rules repo cloned next to this one: `../fairpath-rules/RULES.md`
- Otherwise: `gh api repos/fairPath/fairpath-rules/contents/RULES.md -H "Accept: application/vnd.github.raw"`

The rules repo is private. If neither works, ask the developer for access before writing code.

## Required reuse for all endpoints

When adding or changing any endpoint, including its services and background workers, you must reuse these files whenever the endpoint needs the behavior they own. Extend the existing implementation when needed. Do not create parallel helpers or duplicate this logic in controllers, services, clients, or workers.

Paths are relative to `src/main/java/com/job_search/fair_path/`.

- `services/AiRequestExecution.java` owns shared AI request execution and dispatch to the existing analysis and comparison executors.
- `services/AiServiceErrors.java` owns decoding and classifying AI service failures, including transport errors, timeouts, and correlation mismatches.
- `services/AnalysisErrorCodes.java` owns analysis error codes and their retryability rules.

### Backward compatibility for shared code

When extending a reusable file, check all existing callers and preserve their behavior, including endpoint response contracts and the pages that depend on them. Keep existing method signatures usable, for example by having an existing overload delegate with defaults that preserve its behavior.

For any new parameter, preserve the existing behavior when callers omit it, pass `null`, or send an unset value such as a frontend `undefined` that is omitted from JSON. Handle these cases at the appropriate boundary so existing requests keep working and dependent pages continue to render and function. Verify existing callers and cover the new behavior with the parameter supplied, omitted, and explicitly set to `null` where applicable.

## Done

Done means `./gradlew test` passes. Integration tests run Postgres in Testcontainers, so start Docker first.
