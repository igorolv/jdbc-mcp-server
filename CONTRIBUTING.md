# Contributing

Thank you for your interest in the project.

This repository contains a read-only MCP server for PostgreSQL and Oracle. Changes must preserve
the project's main safety property: tools must not execute write queries against the database.

## Preparing a Change

1. Create a dedicated branch from `main`.
2. Describe the problem or goal in an issue unless it is a small documentation-only change.
3. Keep the pull request focused on one topic.
4. Add or update tests for behavior changes.

## Local Verification

```bash
./gradlew test
```

For a full build:

```bash
./gradlew build
```

Integration tests may require Docker/Testcontainers or real database connection settings. Do not
commit real JDBC credentials, passwords, tokens, or data dumps to the repository.

## SQL Tool Requirements

- Any new tool must be read-only by design and implementation.
- Non-SELECT operations must be blocked before they reach the database.
- Queries that may return large results need a limit or an explicit bound.
- Errors should use the existing JSON shape with a clear `kind`.

## Pull request checklist

- Code follows the existing project style.
- Tests pass locally, or the PR explains why they were not run.
- README or documentation is updated when public behavior changes.
- The PR contains no secrets, real connection strings, or private data.
