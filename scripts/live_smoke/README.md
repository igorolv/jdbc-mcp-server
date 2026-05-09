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
```

`*_SCHEMA` is optional. The runner maps these variables to the server's `JDBC_*` variables and
starts `java -jar build/libs/jdbc-mcp-server.jar`.

Current checks:

- MCP `initialize`
- `tools/list` contains core query tools
- `listSchemas`
- `inspectQuery` on a database-specific ping query
- `validateQuery` on the same ping query
- `executeQuery` on the same ping query
- `resolveQueryLineage` on the ping query
- optional `resolveQueryLineage` on the first visible database view

The scripts use newline-delimited JSON over stdio, which is what the current Spring AI MCP server
starter expects for this application.
