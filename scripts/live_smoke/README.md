# Live MCP Smoke Scripts

These scripts exercise the packaged `jdbc-mcp-server.jar` through the same stdio MCP path used by
real clients. They are intentionally neutral: checks use ping queries and optional metadata
discovery, and they skip database-object-specific checks when the selected schema has no matching
objects.

Build the jar first:

```powershell
.\gradlew.bat bootJar
```

Run Oracle smoke using the existing live environment variables:

```powershell
python scripts/live_smoke/run_smoke.py --db oracle
```

Supported environment variable sets:

```text
LIVE_ORACLE_URL / LIVE_ORACLE_USERNAME / LIVE_ORACLE_PASSWORD / LIVE_ORACLE_SCHEMA
LIVE_POSTGRES_URL / LIVE_POSTGRES_USERNAME / LIVE_POSTGRES_PASSWORD / LIVE_POSTGRES_SCHEMA
LIVE_MSSQL_URL / LIVE_MSSQL_USERNAME / LIVE_MSSQL_PASSWORD / LIVE_MSSQL_SCHEMA
LIVE_SQLITE_URL / LIVE_SQLITE_SCHEMA
```

`*_SCHEMA` is optional; `--schema` overrides it. SQLite needs no credentials, so the whole suite can
run against a local file without any database server:

```powershell
$env:LIVE_SQLITE_URL = 'jdbc:sqlite:C:/data/app.db'
python scripts/live_smoke/run_smoke.py --db sqlite
```

The server reads databases only from `connections.json`, never from the environment. For each run
the script creates a temporary data directory holding a `connections.json` with one entry named
`smoke` (url, username, password, and `defaultSchema` when a schema is known), and starts
`java -jar build/libs/jdbc-mcp-server.jar` with `JDBC_MCP_CONNECTIONS_FILE` and `JDBC_MCP_DATA_DIR`
pointing at it. Every tool call passes `"connection": "smoke"`. The directory — including the file
with the password, the local catalog and the server log — is deleted when the run ends, so nothing
is left in `~/.jdbc-mcp-server`.

Current checks:

- MCP `initialize`
- `tools/list` contains `listConnections` and the core query tools
- `listConnections` reports exactly the `smoke` connection, with no `configError`
- `listSchemas`
- `inspectQuery` on a database-specific ping query
- `validateQuery` on the same ping query
- `executeQuery` on the same ping query
- `resolveQueryLineage` on the ping query
- optional `resolveQueryLineage` on the first visible database view

`initialize`, `tools/list`, `listConnections` and `inspectQuery` do not touch the database, so they
pass even when it is unreachable; the remaining checks then fail with the connection error.

Exit codes: `0` all checks passed or were skipped, `1` at least one check failed, `2` the run could
not start (missing variables, jar not found, server did not start).

The scripts use newline-delimited JSON over stdio, which is what the current Spring AI MCP server
starter expects for this application.
