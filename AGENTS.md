# Agent instructions

## Rules

Read `RULES.md` from `fairPath/fairpath-rules` before starting any ticket or implementation. Every change in this repo follows it.

- With the rules repo cloned next to this one: `../fairpath-rules/RULES.md`
- Otherwise: `gh api repos/fairPath/fairpath-rules/contents/RULES.md -H "Accept: application/vnd.github.raw"`

The rules repo is private. If neither works, ask the developer for access before writing code.

## Done

Done means `./gradlew test` passes. Integration tests run Postgres in Testcontainers, so start Docker first.
