# Contributing

Thank you for your interest in the project.

This repository contains a read-only MCP server for PostgreSQL, Oracle, SQL Server, Firebird,
SQLite, and any other JDBC database in generic mode. Changes must preserve the project's main safety
property: tools must not execute write queries against the database.

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

Do not commit real JDBC credentials, passwords, tokens, or data dumps to the repository.

### Integration tests

Integration tests start real PostgreSQL 16, Oracle 23ai Free, SQL Server 2022, and Firebird 3
instances through Testcontainers, so Docker is required; CI runs them weekly and on demand. They are
excluded from the regular build and run separately (the SQLite suites, including generic JDBC over an
external SQLite driver, need no Docker and run with `./gradlew test`):

```bash
./gradlew integrationTest
```

To run only the SQL Server Testcontainers suite:

```bash
./gradlew integrationTest --tests "*SqlServerIntegration*"
```

> The first Oracle Free and SQL Server runs download large images and may take several minutes to start.

### Smoke tests against a real Oracle database

If you have access to an existing Oracle database, you can run read-only smoke tests
(`LiveOracleIntegrationTest`) directly against it. The tests execute only `SELECT` queries against
the dictionary (`DUAL`, `ALL_TABLES`) and the user schema; there are no
`CREATE` / `INSERT` / `UPDATE` statements.

Username and password are **not stored** in the repository; they are passed through environment
variables. If they are not set, the tests are skipped quietly and do not break the regular build.

```bash
export LIVE_ORACLE_URL='jdbc:oracle:thin:@db.example.com:1521:ORCL'
export LIVE_ORACLE_USERNAME='ai_readonly'
export LIVE_ORACLE_PASSWORD='secret'
# optional, defaults to LIVE_ORACLE_USERNAME uppercased:
# export LIVE_ORACLE_SCHEMA='APP_SCHEMA'

./gradlew liveOracleTest
```

Windows (PowerShell):

```powershell
$env:LIVE_ORACLE_URL      = 'jdbc:oracle:thin:@db.example.com:1521:ORCL'
$env:LIVE_ORACLE_USERNAME = 'ai_readonly'
$env:LIVE_ORACLE_PASSWORD = 'secret'
./gradlew liveOracleTest
```

`.env` is listed in `.gitignore`; if desired, store variables there and load them before running
tests, for example with `direnv`, `dotenv-cli`, or `set -a; . ./.env; set +a` in bash. Gradle does
not parse `.env` itself; variables must already be present in the environment when Gradle starts.

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
