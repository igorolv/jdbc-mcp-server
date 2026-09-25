# JDBC MCP Server

[![CI](https://github.com/igorolv/jdbc-mcp-server/actions/workflows/ci.yml/badge.svg)](https://github.com/igorolv/jdbc-mcp-server/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/igorolv/jdbc-mcp-server?include_prereleases)](https://github.com/igorolv/jdbc-mcp-server/releases/latest)
[![License](https://img.shields.io/github/license/igorolv/jdbc-mcp-server)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21%2B-blue?logo=openjdk)](https://adoptium.net/)
[![MCP](https://img.shields.io/badge/MCP-server-8A2BE2)](https://modelcontextprotocol.io/)
[![Glama score](https://glama.ai/mcp/servers/igorolv/jdbc-mcp-server/badges/score.svg)](https://glama.ai/mcp/servers/igorolv/jdbc-mcp-server)
[![Listed on mcpservers.org](https://mcpservers.org/badge.svg)](https://mcpservers.org/servers/igorolv/jdbc-mcp-server)

MCP server (stdio) that exposes read-only access to relational databases over JDBC: schema
metadata, `SELECT` execution, execution plans, statistics and index analysis. Built-in dialects
for PostgreSQL, Oracle, SQL Server, Firebird and SQLite (drivers bundled); other databases are
served through `DatabaseMetaData` with an external driver.

**Contents:** [Features](#features) ·
[Scope and Limitations](#scope-and-limitations) ·
[Typical Workflows](#typical-workflows) ·
[Quickstart](#quickstart) ·
[Supported Databases](#supported-databases) ·
[Configuring Connections](#configuring-connections) ·
[Connecting an AI Client](#connecting-an-ai-client) ·
[Architecture](#architecture) ·
[MCP Tools](#mcp-tools) ·
[MCP Resources](#mcp-resources) ·
[Error Format](#error-format) ·
[Read-only Protection](#read-only-protection) ·
[Server Environment Variables](#server-environment-variables) ·
[Build](#build) ·
[Project Structure](#project-structure) ·
[Stack](#stack) ·
[Troubleshooting](#troubleshooting) ·
[License](#license)

## Features

- **Engines:** PostgreSQL 11+, Oracle 12c+, SQL Server 2012+, Firebird 3+, SQLite; generic JDBC
  via `driverPath`. Capabilities per engine: [Supported Databases](#supported-databases).
- **Write protection:** JSqlParser AST guard (single `SELECT` / `WITH` / `EXPLAIN`), plus
  session-, transaction- or file-level read-only mode where the engine supports it. Details:
  [Read-only Protection](#read-only-protection).
- **Connections:** any number of databases in one [`connections.json`](#configuring-connections);
  every tool takes a `connection` argument; pools are created on first use. Credentials are not
  read from the environment.
- **Tools:** 49, in 11 [groups](#tool-groups) that can be disabled individually: metadata, query
  execution, plan analysis, column distribution and selectivity, table and index statistics,
  schema context, benchmarks, usage catalog. Reference: [MCP Tools](#mcp-tools).
- **Local catalog:** per-connection SQLite file with a persistent
  [structure snapshot](#persistent-structure-snapshot) and an index of known application queries
  ([usage catalog](#usage-catalog)).
- **Clients:** any MCP client with stdio transport; configuration examples for Claude Code, Codex
  CLI, OpenCode, VS Code, Copilot CLI and Cursor in
  [Connecting an AI Client](#connecting-an-ai-client).

## Scope and Limitations

- Read-only: no DML, DDL or migrations; `EXPLAIN ANALYZE` (PostgreSQL) executes the query inside a
  read-only transaction.
- stdio transport only; Java 21+.
- Non-JDBC databases are not supported.
- Integration tests run in CI against PostgreSQL 16, Oracle 23ai Free, SQL Server 2022 and
  Firebird 3 (Testcontainers).

## Typical Workflows

Query authoring: `listConnections` → `schemaBrief` or `queryContext` → `describeTable` →
`findJoinPaths` → `validateQuery` → `executeQuery`.

Plan and index analysis: `analyzePlan` → `tableStats`, `indexStats` → `estimateSelectivity`,
`joinCardinality`, `columnDistribution` → `fkIndexCoverage`, `redundantIndexes`,
`unusedIndexes` → `benchmarkQuery`.

## Quickstart

**1. Get the jar** — download `jdbc-mcp-server.jar` from the
[latest release](https://github.com/igorolv/jdbc-mcp-server/releases/latest) (JDK 21+ required;
all JDBC drivers are bundled), or build it yourself:

```bash
./gradlew bootJar   # → build/libs/jdbc-mcp-server.jar
```

**2. Describe your databases** in `~/.jdbc-mcp-server/connections.json`:

```json
{
  "connections": {
    "myapp": {
      "url": "jdbc:postgresql://db.example.com:5432/myapp",
      "username": "ai_readonly",
      "password": "secret",
      "description": "Application database — customers, orders, shipments"
    }
  }
}
```

Entries for Oracle, SQL Server, Firebird, SQLite and generic JDBC look the same — see
[the connections file](#the-connections-file). Use a
[read-only database user](#maximum-protection-use-a-read-only-database-user): it is the only
protection that does not depend on this server.

**3. Register the server** with your MCP client — with no database settings in the client config:

```json
{
  "command": "java",
  "args": ["-jar", "<absolute-path>/jdbc-mcp-server.jar"],
  "env": {}
}
```

For Claude Code and Codex CLI that is one command:

```bash
claude mcp add --scope user jdbc -- java -jar /path/to/jdbc-mcp-server.jar
codex mcp add jdbc -- java -jar /path/to/jdbc-mcp-server.jar
```

OpenCode, VS Code with Copilot, Copilot CLI, Cursor and others:
[Connecting an AI Client](#connecting-an-ai-client).

**4. Ask the agent for `listConnections`.** It answers with the databases this server serves; every
other tool takes that name as its first argument:

```json
{"connection": "myapp", "sql": "SELECT count(*) FROM orders"}
```

Full details: [Configuring Connections](#configuring-connections) and the detailed
[connections guide](docs/connections.md).

## Supported Databases

Every tool works on every engine unless the table says otherwise. The engine is chosen from the
JDBC URL prefix; `dialect` in the connection entry overrides it.

| | PostgreSQL | Oracle | SQL Server | Firebird 3+ | SQLite | Generic JDBC |
|---|---|---|---|---|---|---|
| URL prefix | `jdbc:postgresql:` | `jdbc:oracle:` | `jdbc:sqlserver:` | `jdbc:firebirdsql:` | `jdbc:sqlite:` | any, with `driverPath` |
| Driver | bundled | bundled | bundled | bundled (Jaybird) | bundled | your jar |
| Schemas | native | native | native | one logical `PUBLIC` | one: `main` | native, or one logical |
| Read-only enforced by | session `default_transaction_read_only` | guard + read-only user | guard + read-only user | read-only transactions | file opened read-only | guard + driver `setReadOnly` |
| Plans | `EXPLAIN [ANALYZE]` | `EXPLAIN PLAN` | `SHOWPLAN` (estimated) | Jaybird plan, no costs | `EXPLAIN QUERY PLAN`, no costs | — |
| Row estimates (`estimateSelectivity`, `joinCardinality`) | planner | planner | planner | exact `COUNT(*)` | exact `COUNT(*)` | exact `COUNT(*)` |
| View / trigger sources | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| Routines | ✓ | ✓ (packages too) | ✓ | ✓ (packages, UDFs) | none in SQLite | listed, no source |
| Sequences | ✓ | ✓ | ✓ | ✓ (generators) | `AUTOINCREMENT` counters | — |
| Table / index sizes | ✓ | best-effort | ✓ | — | `dbstat` | — |
| `unusedIndexes` | ✓ | — | — | — | — | — |

"—" means the tool answers with error kind `unsupported` or a note saying why.

### URL examples

```text
jdbc:postgresql://db.example.com:5432/myapp
jdbc:postgresql://db.example.com:5432/myapp?currentSchema=public&sslmode=require

jdbc:oracle:thin:@//db.example.com:1521/ORCLPDB1
jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=...)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=...)))

jdbc:sqlserver://db.example.com:1433;databaseName=myapp;encrypt=true;trustServerCertificate=false
jdbc:sqlserver://db.example.com;instanceName=SQLEXPRESS;databaseName=myapp

jdbc:firebirdsql://db.example.com:3050//var/lib/firebird/data/myapp.fdb
jdbc:firebirdsql://db.example.com/myapp?encoding=WIN1251

jdbc:sqlite:/data/app.db
jdbc:sqlite:C:/data/app.db

jdbc:mysql://db.example.com:3306/shop           (generic, with driverPath)
jdbc:h2:tcp://db.example.com/~/inventory        (generic, with driverPath)
```

More per-engine recipes — SSL, SIDs and TNS aliases, named instances, Windows paths, drivers for
MySQL / MariaDB / H2 / Db2 — are in [docs/connections.md](docs/connections.md#recipes-per-engine).

The notes below say, per engine, how read-only is enforced, how names are matched, where plans and
statistics come from, and what the database user needs. Firebird, SQLite and generic JDBC differ the
most from what you may expect — read their notes before using them.

### PostgreSQL

PostgreSQL 11 and later (the integration tests run on 16), through the bundled pgjdbc driver.

- **Read-only inside the database.** The server appends
  `options=-c default_transaction_read_only=on` to the URL, so every transaction of the session is
  read-only in PostgreSQL itself and even DDL is rejected. A URL that sets its own `options=` is
  left untouched and loses this protection; add the setting to your `options` value yourself — see
  the [PostgreSQL recipe](docs/connections.md#postgresql).
- **Names are matched as stored.** Unquoted identifiers are stored in lower case: pass `orders`, not
  `ORDERS`; a table created as `"Orders"` is `Orders`.
- **Plans.** `explainQuery` runs `EXPLAIN (VERBOSE, COSTS)`, `analyzePlan` reads the JSON form.
  `analyze=true` adds `ANALYZE` (plus `BUFFERS` for `analyzePlan`) — the query is then **executed**,
  inside the read-only transaction; it is the only way to get actual row counts and estimate errors.
- **Statistics** come from `pg_class`, `pg_stat_user_tables` and `pg_stat_user_indexes`: sizes
  including TOAST, live and dead tuples, last (auto)vacuum / analyze, sequential vs. index scans.
  PostgreSQL is the only engine with `unusedIndexes`; its counters run since the last statistics
  reset.
- **`timedQuery`** adds per-query deltas from `pg_stat_statements` when the extension is installed
  (PostgreSQL 13+ column names); without it the response says `available: false`.
- **Catalog.** Partitioned tables, materialized views and foreign tables, `EXCLUDE` constraints,
  comments from `pg_description`, function and procedure sources from `pg_get_functiondef`.
- **User:** `CONNECT` on the database, `USAGE` on the schemas, `SELECT` on the tables — see the
  [read-only role snippet](#maximum-protection-use-a-read-only-database-user).

### Oracle

Oracle Database 12c and later (the integration tests run on 23ai Free), through the bundled
`ojdbc11` driver.

- **Read-only is up to the guard and the user.** The Oracle driver treats `setReadOnly(true)` as a
  hint. The guard lets only `SELECT` / `WITH` / `EXPLAIN` through; a
  [read-only user](#maximum-protection-use-a-read-only-database-user) is the real protection.
- **Names are upper case.** Unquoted identifiers fold to upper case and the server passes names
  unquoted: use `CUSTOMERS` and `defaultSchema: "APP_OWNER"`. Without `defaultSchema` the current
  user's schema is used — rarely the one that owns the application tables.
- **Catalog from the `ALL_*` views**, so the server sees exactly what the user has been granted.
  Table comments come through the driver's `remarksReporting`, column comments from
  `ALL_COL_COMMENTS`. Column defaults are `LONG` values read through `DBMS_XMLGEN`, which is why
  `rebuildCatalog` has its own `structureSnapshotOracleColumnQueryTimeoutSeconds`. For a package,
  `getRoutineDefinition` returns the body rather than the spec.
- **Plans.** `EXPLAIN PLAN SET STATEMENT_ID … FOR` into `PLAN_TABLE` (a session-private temporary
  table in current versions, usable by a read-only user), displayed with `DBMS_XPLAN.DISPLAY`. Plans
  are static optimizer estimates; `analyze` is ignored.
- **Statistics** reflect the last `DBMS_STATS` gather (`last_analyzed`): row counts from
  `ALL_TABLES`, index `distinct_keys`, `clustering_factor`, `blevel`, `leaf_blocks`. Sizes need
  `DBA_SEGMENTS` (for example via `SELECT_CATALOG_ROLE`) and are left out without it.
  `unusedIndexes` is unsupported — `ALL_INDEXES` has no scan counters; the response points to
  `DBA_INDEX_USAGE` (12.2+) and `ALTER INDEX … MONITORING USAGE`.
- **User:** `CREATE SESSION`, `SELECT` on the application tables (directly or through a role), and
  `SELECT ANY DICTIONARY` or `SELECT_CATALOG_ROLE` for metadata and sizes.

### SQL Server

SQL Server 2012 and later (the integration tests run on 2022), through the bundled `mssql-jdbc`
driver.

- **Read-only is up to the guard and the login.** The driver treats `setReadOnly(true)` as a hint;
  use a login whose user has only `SELECT`.
- **Encryption is on by default.** Current `mssql-jdbc` versions default to `encrypt=true`, so a
  server with a self-signed certificate fails the TLS handshake. Install a trusted certificate, or
  add `trustServerCertificate=true` on local and dev servers only.
- **Names** are always bracket-quoted; whether case matters follows the database collation (usually
  it does not). Without `defaultSchema` the user's default schema (`SCHEMA_NAME()`, usually `dbo`)
  is used.
- **Catalog from the `sys.*` views.** View, routine and trigger sources come from `sys.sql_modules`
  and are visible only with `VIEW DEFINITION`. Comments are the `MS_Description` extended
  properties.
- **Plans.** `SET SHOWPLAN_TEXT ON` / `SET SHOWPLAN_XML ON` on the same session: the statement is
  compiled, not executed, and the plan is an estimate — there is no actual-plan mode. Needs the
  `SHOWPLAN` permission.
- **Statistics** come from `sys.tables`, `sys.indexes`, `sys.partitions` and the allocation units:
  row counts and sizes. Index usage counters (`sys.dm_db_index_usage_stats`) need server-level state
  permissions, so the server does not read them and `unusedIndexes` answers with a note.
- **User:** `SELECT` on the schema, `VIEW DEFINITION`, `SHOWPLAN` — see the
  [login snippet](#maximum-protection-use-a-read-only-database-user).

### Firebird

Firebird 3.0 and later, through Jaybird 6 (bundled). Connect over the network with the pure-Java
driver — `jdbc:firebirdsql://<host>:3050//<path/to/db.fdb>` — to a Firebird server; no native
client library is needed. An embedded database (a `.fdb` / `.gdb` file opened in-process) can be
served by starting a Firebird server of the matching version on a **copy** of the file (Firebird 3
for ODS 12, Firebird 4/5 for ODS 13); the official `firebirdsql/firebird` Docker image works:

```bash
docker run -d --name fb3 -e FIREBIRD_ROOT_PASSWORD=<pw> \
  -v /path/to/copy:/var/lib/firebird/data -p 3050:3050 firebirdsql/firebird:3.0.14
```

```json
"legacy": {
  "url": "jdbc:firebirdsql://localhost:3050//var/lib/firebird/data/app.gdb",
  "username": "SYSDBA",
  "password": "<pw>"
}
```

How Firebird differs from the other engines:

- **No schemas.** Firebird before 6.0 has none, so the database is presented as one logical schema,
  `PUBLIC` (the schema Firebird 6 moves existing objects into). Omit `schema` or pass `PUBLIC`;
  any other name is an `argument` error. Generated SQL never qualifies names with it.
- **Identifiers.** Unquoted names are stored in upper case: pass `CUSTOMERS`, not `customers`.
- **Encoding.** Unless the URL sets `encoding=` / `charSet=` / `lc_ctype=`, the server adds
  `encoding=UTF8`, and Firebird converts from each column's character set (e.g. `WIN1251`).
- **Read-only** is enforced by the server: Jaybird runs read-only transactions, which reject DML
  and DDL (`attempted update during read-only transaction`).
- **Plans** come from the prepared statement through Jaybird (Firebird has no `EXPLAIN`); they
  carry no costs or row estimates, so `analyzePlan` reports every full scan.
- **`estimateSelectivity` / `joinCardinality`** execute exact `COUNT(*)` queries instead of
  planner estimates (bounded by `queryTimeoutSeconds`); the `note` says so.
- **`columnHistogram`** computes discrete percentiles (`percentile_disc`) with window functions.
- **Statistics** are limited to index selectivity as of the last `SET STATISTICS` / restore:
  `tableStats.estimatedRows` is derived from the most selective unique index, or an exact
  `COUNT(*)` when the table has none; there are no sizes or usage counters, and `unusedIndexes` is
  unsupported.
- **Routines** list stored procedures, PSQL functions, packages and legacy UDFs; a UDF's
  "definition" is its library entry point.

### SQLite

A plain `jdbc:sqlite:/path/to/app.db` URL is served by the bundled sqlite-jdbc driver — the one the
server's own catalog uses, so the SQLite version is the one that driver embeds (3.51.3). No
`driverPath` and no credentials are needed.

- **Read-only by the database.** Unless the URL sets `open_mode` itself, the server adds
  `open_mode=1` (`SQLITE_OPEN_READONLY`): writes fail inside SQLite (`attempt to write a readonly
  database`), and a mistyped path is an error instead of a new, empty database file.
- **Schema `main`.** SQLite's JDBC driver reports no schemas, so the database is presented as the one
  schema SQLite itself calls `main` (`main.orders` is valid SQL). Omit `schema` or pass `main`.
  Attached databases are not listed.
- **Catalog.** View and trigger definitions are the `CREATE` texts from `sqlite_schema`; keys, UNIQUE
  constraints and indexes come from the `pragma_*` functions, sizes from `dbstat`. SQLite keeps no
  constraint names: the primary key has none, foreign keys are named `fk_<table>_<n>`. CHECK
  constraints live only in the `CREATE TABLE` text and are not reported. "Sequences" are the
  `AUTOINCREMENT` counters, named after their table. SQLite has no stored routines.
- **Plans** come from `EXPLAIN QUERY PLAN` (the query is prepared, not run), rendered like the
  `sqlite3` shell; they carry no costs or row estimates, so `analyzePlan` reports every full scan.
- **Row counts** are exact `COUNT(*)` — in `tableStats`, `estimateSelectivity` and `joinCardinality`
  alike; `columnHistogram` computes discrete percentiles with window functions.

### Generic JDBC

Any other database with a JDBC driver — H2, HSQLDB, Derby, DB2, MySQL/MariaDB, Informix, and so on —
can be served in **generic** mode. Point `driverPath` at the driver jar (or a directory of
jars); a URL no built-in dialect recognizes is then served as generic JDBC. `"dialect": "generic"`
forces generic mode even for a URL a built-in dialect would take.

```json
"inventory": {
  "url": "jdbc:h2:tcp://db.example.com/~/inventory",
  "username": "reader",
  "password": "<pw>",
  "driverPath": "drivers/h2-2.3.232.jar",
  "description": "Legacy inventory (H2)"
}
```

| Field | Meaning |
|---|---|
| `driverPath` | A driver jar, or a directory whose `*.jar` files are all loaded. Relative paths are relative to `connections.json`. The jars get a class loader of their own, so they never clash with the bundled drivers |
| `driverClass` | The `java.sql.Driver` class; optional — by default the registered driver that accepts the URL is used |
| `dialect` | `postgresql`, `oracle`, `mssql`, `firebird`, `sqlite`, or `generic`; optional — by default detected from the URL. `driverPath` also works with a built-in dialect, e.g. a newer Oracle driver |

Generic mode answers from `DatabaseMetaData` and portable SQL only, so it is slower and less
complete than a real dialect:

- **Works:** schemas, tables, columns, primary and foreign keys, indexes, `describeTable`, the schema
  context tools, queries, samples, `columnStats` / `columnDistribution` / `nullRatio`, FK index
  coverage, redundant indexes, and routines listed without sources.
- **Slower substitutes:** `estimateSelectivity` and `joinCardinality` run exact `COUNT(*)` queries
  (bounded by `queryTimeoutSeconds`); `tableStats` counts rows unless the driver reports a table
  statistic; `columnHistogram` streams the sorted column to the server and picks discrete percentiles.
- **Unsupported** (error kind `unsupported`): plans (`explainQuery`, `analyzePlan`), view / routine /
  trigger definitions, sequences, and unused-index detection. CHECK constraints and triggers are not
  reported.
- **No schemas?** A database without them (MySQL, for instance) is presented as one logical schema:
  its current catalog (MySQL's database) or `PUBLIC`.
- **Read-only is best-effort:** the guard, plus `Connection.setReadOnly` where the driver honours it.
  Make the connection itself read-only where the driver allows it (a read-only URL option), or use
  a read-only database user.

## Configuring Connections

Every database this server serves is described in one JSON file. Nothing about a database — URL,
credentials, schema, timeouts, limits — comes from the environment. This section covers what most
setups need; [docs/connections.md](docs/connections.md) is the detailed guide: recipes per engine,
environments, secrets, external drivers, Docker, tuning, and a reference of configuration errors.

### The connections file

The default path is `~/.jdbc-mcp-server/connections.json` (`%USERPROFILE%\.jdbc-mcp-server\connections.json`
on Windows, `/data/connections.json` in the Docker image). `JDBC_MCP_CONNECTIONS_FILE` points
elsewhere. The directory around it is the server's data directory: each connection gets a
`<name>/` subdirectory for its local catalog, and the log is written to `logs/`.

```json
{
  "connections": {
    "orders": {
      "url": "jdbc:postgresql://db.example.com:5432/orders",
      "username": "ai_readonly",
      "password": "secret",
      "defaultSchema": "public",
      "description": "Order service — customers, orders, shipments",
      "structureSnapshotSchemas": ["public", "nsi"]
    },
    "billing": {
      "url": "jdbc:oracle:thin:@//oracle.example.com:1521/BILLING",
      "username": "AI_READONLY",
      "password": "${BILLING_DB_PASSWORD}",
      "defaultSchema": "BILLING_OWNER",
      "description": "Legacy billing (Oracle)"
    },
    "crm": {
      "url": "jdbc:sqlserver://sql.example.com:1433;databaseName=crm;encrypt=true",
      "username": "ai_readonly",
      "password": "secret",
      "defaultSchema": "dbo",
      "description": "CRM (SQL Server)"
    },
    "legacy": {
      "url": "jdbc:firebirdsql://fb.example.com:3050//var/lib/firebird/data/app.fdb",
      "username": "SYSDBA",
      "password": "secret",
      "description": "Pre-2010 warehouse app (Firebird)"
    },
    "analytics": {
      "url": "jdbc:sqlite:/srv/data/analytics.db",
      "description": "Nightly analytics extract (SQLite)"
    },
    "shop": {
      "url": "jdbc:mysql://mysql.example.com:3306/shop",
      "username": "ai_readonly",
      "password": "secret",
      "driverPath": "drivers/mysql-connector-j-9.1.0.jar",
      "description": "Web shop (MySQL, generic JDBC)"
    }
  }
}
```

`url` is the only required field; the engine is detected from its prefix (`jdbc:postgresql:`,
`jdbc:oracle:`, `jdbc:sqlserver:`, `jdbc:firebirdsql:` / `jdbc:firebird:`, `jdbc:sqlite:`). Any other
URL needs a `driverPath` and is served as [generic JDBC](#generic-jdbc); `dialect` overrides the
detection. `description` is free text returned by `listConnections`, so an agent can pick a database
by meaning rather than by name; include the stand and any restriction ("PRODUCTION — keep queries
small").

When the file is missing or defines no connection the server still starts (so an MCP client can list
its tools), logs a warning, and `listConnections` returns an empty list; every other tool then
reports that no connection is available. A file that is present but malformed is a startup error.
Keys the server does not know are ignored without a warning, so a misspelled field silently keeps
its default.

### Connection names

The object key is the connection name: the value an agent passes as `connection`, the name of the
local catalog directory (`<data-dir>/<name>/`), and part of MCP resource URIs. It must match
`[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?`, be at most 64 characters, and not be `.` or `..`.

The optional `@` is there to name the two axes separately: `<service>@<stand>`, as in `ssj@dev`,
`nsi@dev`, `ssj@tst`. A dash cannot do that job, because dashes already occur inside service names
(`ssj-ws`, `ssj-ek-export`, `ais-ui`), so `ssj-ws-dev` is ambiguous to a human and to a model alike.
`@` never occurs in a service name and reads as "what, where" the way `user@host` does. It is
percent-encoded to `%40` in resource URIs; nothing else about the name changes — the directory on
disk is the name as written.

Because the catalog is keyed by name, renaming a connection starts it with an empty catalog, and two
databases must never share a name — see
[Changing a configuration](docs/connections.md#changing-a-configuration).

### Connection fields

Everything except `url` is optional; a field left out falls back to the built-in default:

| Field | Default | Meaning |
|---|---|---|
| `url` | required | JDBC URL; also selects the engine |
| `username`, `password` | none | Database credentials |
| `description` | none | Free text returned by `listConnections` |
| `defaultSchema` | the session schema | Schema used when a metadata tool call omits one |
| `dialect` | from the URL | `postgresql`, `oracle`, `mssql`, `firebird`, `sqlite`, or `generic` — see [Generic JDBC](#generic-jdbc) |
| `driverPath`, `driverClass` | bundled drivers | Load the JDBC driver from a jar or directory instead — see [Generic JDBC](#generic-jdbc) |
| `queryTimeoutSeconds` | `30` | Per-query timeout; `0` disables |
| `maxRows` | `1000` | Row cap for one response; `truncated: true` when hit |
| `fetchSize` | `500` | JDBC `fetchSize` hint |
| `readonlyGuard` | `strict` | `off` disables the client-side SELECT-only check |
| `poolMaximumSize` | `40` | Hikari maximum pool size |
| `poolMinimumIdle` | `0` | Hikari minimum idle; `0` keeps the pool lazy |
| `poolConnectionTimeoutMs` | `10000` | Hikari connection checkout timeout |
| `poolValidationTimeoutMs` | `5000` | Hikari validation timeout |
| `poolIdleTimeoutMs` | `60000` | Idle connections above `poolMinimumIdle` are closed after this |
| `structureSnapshotSchemas` | the default schema | Schemas captured by `rebuildCatalog` |
| `structureSnapshotOracleColumnQueryTimeoutSeconds` | `300` | Oracle-only timeout for the bulk column query during `rebuildCatalog`; `0` disables |
| `usageCatalogEnabled` | `true` | When `false`, usage tools report the disabled state |
| `usageCatalogPaths` | none | Extra directories, JSON files or zip archives with QueryUsage records |
| `usageNativeSchemas` | the default schema | Schemas scanned for native usage |
| `usageNativeIncludeViews`, `usageNativeIncludeRoutines`, `usageNativeIncludeTriggers` | `true` | What native usage scanning covers |
| `usageNativeMaxObjects` | `10000` | Maximum native usage records per index build |

Guidance on choosing values — lower `maxRows` and timeouts for production, pool sizes, which schemas
to snapshot — is in [Tuning a connection](docs/connections.md#tuning-a-connection). The
`JDBC_MCP_TOOLS_*` group flags stay in the environment — they shape the tool manifest, which is
shared by all connections. See [Server Environment Variables](#server-environment-variables).

### Passwords and `${VAR}` references

Any string value may reference an environment variable as `${VAR}`. A referenced variable that is
not set fails startup with a message naming the variable and the field; it never becomes an empty
password. Read the next section before reaching for it.

Keep the file readable only by its owner — `chmod 600 ~/.jdbc-mcp-server/connections.json`; for the
Windows equivalent and wrapper-script examples see [Secrets](docs/connections.md#secrets).

### Why credentials live in a file, not in environment variables

The point of this server is that the agent reaches the database *only* through it: every statement
goes through the read-only guard, every result is capped by `maxRows`, and nothing but
`SELECT` / `WITH` / `EXPLAIN` gets through.

Credentials in environment variables undermine exactly that. They are set on the server process by
the MCP client, which means they also sit in the client's own configuration — a file agents read and
edit as a matter of routine — and in the environment of whatever shell launched it. An agent that
has seen a URL, a user and a password does not need the tools any more: `psql`, `sqlplus`, `sqlcmd`
or three lines of Python connect straight to the database, with no guard, no row cap and no trace in
this server's log.

So the server accepts no database credentials from the environment at all — there are no `JDBC_URL`
/ `JDBC_USERNAME` / `JDBC_PASSWORD` variables. They live in `connections.json`, which only the server
reads.

Be clear about what that does and does not buy:

- It removes the easy path. Credentials stop being part of the material an agent routinely handles:
  MCP client configs, shell environment, `env` dumps in logs and bug reports.
- **It is not a sandbox.** An agent with shell access running as you can read the file; `chmod 600`
  keeps out other users, not a process running as your user.
- The guarantee that survives everything is a
  [read-only database user](#maximum-protection-use-a-read-only-database-user). The file narrows the
  attack surface; the database's own permissions close it.

For the same reason, prefer a literal password in the file over a `${VAR}` reference whose variable
would be set in the MCP client's `env` block — that puts the secret straight back where the agent
looks. `${VAR}` earns its place when the value is injected from outside the agent's reach (a systemd
unit, a wrapper script, a secret manager), or when the file itself is shared or committed and the
secret must not be.

### Several databases in one server

One server process can serve any number of named databases. The tool manifest stays a single set of
49 tools no matter how many are configured — each tool takes `connection` as its first argument —
and a database's pool, local catalog and services are created the first time something actually asks
for that connection.

With one registered instance per database, each instance adds its own tool manifest to the agent's
context and runs its own JVM, whether or not the session uses that database.

**Choosing a connection.** There is no default connection: every tool call names the database it
means in its first argument, including installations that serve exactly one. A missing or unknown
name returns an `argument` error listing the available names. `listConnections` shows what exists —
it reads configuration only, so it works even when some of the configured databases are down.

**Isolation.**

- Configuring a connection costs nothing until it is used: no pool, no catalog file, no connection.
- Reaching database `X` opens pools for `X` only.
- A database that is down, or an entry whose URL is not a supported JDBC URL, fails the calls made
  against it and leaves the other connections working. `listConnections` reports the reason in
  `configError`.
- Each connection keeps its own local catalog at `<data-dir>/<name>/<name>.db`, so structure
  snapshots and usage indexes never mix.
- MCP resources (when `JDBC_MCP_RESOURCES_ENABLED=true`) are published for every configured
  connection that already has a local catalog file; URIs are catalog-qualified.

The single server process keeps its shared rolling log under
`<data-dir>/logs/jdbc-mcp-server.log`. Log entries emitted while handling a tool call include its
`connection` name; process-level entries use `connection=server`.

**One instance per database** still works and remains a reasonable choice for one or two databases.
The client namespaces tools by server key, at the cost of one tool manifest and one JVM per database:

```json
{
  "mcpServers": {
    "jdbc-orders": {
      "command": "java",
      "args": ["-jar", "<absolute-path>/jdbc-mcp-server.jar"],
      "env": {"JDBC_MCP_CONNECTIONS_FILE": "<absolute-path>/orders-connections.json"}
    },
    "jdbc-billing": {
      "command": "java",
      "args": ["-jar", "<absolute-path>/jdbc-mcp-server.jar"],
      "env": {"JDBC_MCP_CONNECTIONS_FILE": "<absolute-path>/billing-connections.json"}
    }
  }
}
```

Do not give two databases the same connection name, in either setup: their usage index and structure
snapshot would share one `<catalog>.db` file. Several stands of one service, per-project connection
files and naming conventions are covered in
[Several databases and environments](docs/connections.md#several-databases-and-environments).

### Checking the configuration

1. **Run the jar by hand** — `java -jar jdbc-mcp-server.jar < /dev/null` — when an MCP client only
   says it failed to connect. A malformed file, an invalid name or an unset `${VAR}` is reported
   there.
2. **Read the startup line.** The server logs every entry with its URL (a `password=` parameter is
   masked) and, for an entry it cannot use, the reason:

   ```text
   Configured connections: orders -> jdbc:postgresql://db.example.com:5432/orders, shop -> jdbc:mysql://mysql.example.com:3306/shop (unusable: driverPath /home/me/.jdbc-mcp-server/drivers/mysql-connector-j-9.1.0.jar does not exist)
   ```

3. **Ask the agent for `listConnections`.** Each entry shows its `kind`, `defaultSchema`, whether a
   local catalog exists, and `configError` when it cannot be used.
4. **Make one real call per connection**, e.g. `listSchemas`. Host, credentials and driver loading
   are checked only when a connection is first used.

The file is read once at startup: after editing it, restart or reconnect the MCP server. Every
error message and its cause is listed in
[Configuration errors](docs/connections.md#configuration-errors).

## Connecting an AI Client

The server is a local stdio process, so every MCP client registers it the same way: the command is
`java`, the arguments are `-jar <absolute-path>/jdbc-mcp-server.jar`, and there is no environment to
set. The databases come from [`connections.json`](#configuring-connections); credentials are kept
out of the client config on purpose — see
[why](#why-credentials-live-in-a-file-not-in-environment-variables). Add
`JDBC_MCP_CONNECTIONS_FILE` only if you keep the file somewhere other than the default path.

| Client | Where the server is registered |
|---|---|
| [Claude Code](#claude-code) | `claude mcp add` → `~/.claude.json` (user) or `.mcp.json` (project) |
| [Codex CLI](#codex-cli) | `~/.codex/config.toml` |
| [OpenCode](#opencode) | `~/.config/opencode/opencode.json` (global) or `opencode.json` (project) |
| [VS Code with GitHub Copilot](#vs-code-with-github-copilot) | `.vscode/mcp.json` (workspace) or the user `mcp.json` |
| [GitHub Copilot CLI](#github-copilot-cli) | `~/.copilot/mcp-config.json` |
| [Cursor, Claude Desktop, Qwen Code](#other-clients) | the client's `mcpServers` JSON |

Once registered, ask the agent to call `listConnections`; it should list the entries of your
`connections.json`.

### Claude Code

```bash
claude mcp add --scope user jdbc -- java -jar /path/to/jdbc-mcp-server.jar
```

`--scope user` makes the server available in every project (stored in `~/.claude.json`); without it
the server is added to the current project only. The `--` separates Claude Code's own options from
the server command. `claude mcp list` shows whether the server started; inside a session, `/mcp`
shows its status and reconnects it after you edit `connections.json`.

To share the registration with a team, commit a project-scoped `.mcp.json`:

```json
{
  "mcpServers": {
    "jdbc": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/jdbc-mcp-server.jar"]
    }
  }
}
```

### Codex CLI

```bash
codex mcp add jdbc -- java -jar /path/to/jdbc-mcp-server.jar
```

or directly in `~/.codex/config.toml`:

```toml
[mcp_servers.jdbc]
command = "java"
args = ["-jar", "/path/to/jdbc-mcp-server.jar"]
# rebuildCatalog on a large schema can take minutes; the default tool timeout is 60 s
tool_timeout_sec = 600
```

On Windows, write the path as a TOML literal string so backslashes need no escaping:
`args = ["-jar", 'C:\tools\jdbc-mcp-server.jar']`. Codex gives a server 10 s to start
(`startup_timeout_sec`); the JVM usually needs 2–3 s, but raise it on a slow machine — a server that
misses the deadline is silently left out.

### OpenCode

In `~/.config/opencode/opencode.json` (or `opencode.jsonc`), or a project's `opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jdbc": {
      "type": "local",
      "command": ["java", "-jar", "/path/to/jdbc-mcp-server.jar"],
      "enabled": true
    }
  }
}
```

`command` is one array holding the program and its arguments. OpenCode waits 5 s for the tool list
by default; add `"timeout": 15000` (milliseconds) if the server shows up without tools on a slow
start.

### VS Code with GitHub Copilot

In `.vscode/mcp.json` for one workspace, or in the user-level `mcp.json` opened
with the **MCP: Open User Configuration** command for all workspaces:

```json
{
  "servers": {
    "jdbc": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/jdbc-mcp-server.jar"]
    }
  }
}
```

The top-level key is `servers`, not `mcpServers`. The tools are used by Copilot Chat in agent mode.
**MCP: List Servers** starts, stops and restarts the server and shows its output.

### GitHub Copilot CLI

In `~/.copilot/mcp-config.json` (or interactively with `/mcp add`):

```json
{
  "mcpServers": {
    "jdbc": {
      "type": "local",
      "command": "java",
      "args": ["-jar", "/path/to/jdbc-mcp-server.jar"],
      "tools": ["*"]
    }
  }
}
```

A project can also carry the configuration in `.mcp.json` or `.github/mcp.json`.

### Other clients

Cursor (`.cursor/mcp.json` or `~/.cursor/mcp.json`), Claude Desktop
(`claude_desktop_config.json`) and Qwen Code (`~/.qwen/settings.json`) all use the common
`mcpServers` shape:

```json
{
  "mcpServers": {
    "jdbc": {
      "command": "java",
      "args": ["-jar", "/path/to/jdbc-mcp-server.jar"]
    }
  }
}
```

### Tips for every client

- **Use absolute paths.** The client picks the working directory, so a relative jar path breaks.
  In JSON, write Windows paths with forward slashes (`C:/tools/jdbc-mcp-server.jar`) or doubled
  backslashes.
- **Java 21+.** If `java` on the `PATH` is older, put the full path of a JDK 21+ binary in
  `command`, e.g. `C:/Users/me/.jdks/jdk-21/bin/java.exe` or `/usr/lib/jvm/java-21/bin/java`.
- **Restart after editing `connections.json`** — the file is read once at startup.
- **When the client only says "failed to start"**, run the same command in a terminal; see
  [Checking the configuration](#checking-the-configuration).
- **Docker instead of a local JDK:** the command is `docker` with the arguments
  `run -i --rm -v /home/me/.jdbc-mcp-server:/data ghcr.io/igorolv/jdbc-mcp-server:latest` — see
  [Docker](#docker).

### Running by Hand

```bash
java -jar jdbc-mcp-server.jar
```

(Use `build/libs/jdbc-mcp-server.jar` if you built it locally, or the file downloaded from
[Releases](https://github.com/igorolv/jdbc-mcp-server/releases/latest).)

The server immediately starts listening for MCP over stdin/stdout; no port is opened. Logs are
written to stderr and to `<data-dir>/logs/`. Running it by hand is mostly useful to
[check a configuration](#checking-the-configuration); `Ctrl-D` stops it.

### Docker

The image is published to GHCR with every release. Mount the directory holding `connections.json`
at `/data` — it is also where the server keeps its local catalogs and logs:

```bash
docker run -i --rm -v ~/.jdbc-mcp-server:/data ghcr.io/igorolv/jdbc-mcp-server:latest
```

The same command is what an MCP client should launch (`-i` keeps stdin open for the stdio
transport). JDBC URLs in `connections.json` must be reachable from inside the container: use the
database host name, not `localhost`, or add `--network host` on Linux. SQLite files and driver jars
must be inside the mounted directory or mounted separately — see
[Running in Docker](docs/connections.md#running-in-docker). To build the image locally:

```bash
docker build -t jdbc-mcp-server .
```

## Architecture

```text
                                                  +------------+
                                            +---> | database A |
+-------------+     stdio      +----------+ |     +------------+
|  AI agent   | <------------> | jdbc-mcp | |     +------------+
| (Claude Code|  stdin/stdout  |  server  |-+---> | database B |
|  Cursor...) |                |  (Java)  | |     +------------+
+-------------+                +----------+ |
                                            +---> ...

                                          read-only JDBC
                              PG / Oracle / SQL Server / Firebird / SQLite / any JDBC
```

The protocol is `stdio` only. The client starts the server as a child process. One process serves
any number of named databases, all declared in the
[connections file](#configuring-connections); a connection's pool is opened the first time a tool
call names it.

Tool calls on one stdio session run sequentially. The MCP Java SDK 2.0.0 used by Spring AI 2.0.1
can lose responses when several concurrently executed tools finish at the same time, so the server
keeps `immediateExecution(true)` until the SDK fixes this. A client's `notifications/cancelled` is
not propagated to JDBC `Statement.cancel()`; the configured `queryTimeoutSeconds` (or a tool call's
`timeoutSeconds` override) remains the server-side limit for a running SQL statement.

## MCP Tools

The 49 tools are grouped below by purpose.

Every tool takes `connection` as its **first, required** argument, naming the database to run
against — including installations that serve exactly one database. `listConnections` lists the
names. See [Several databases in one server](#several-databases-in-one-server).

### Tool Groups

Tools are organised into groups that can be turned on or off independently with
`JDBC_MCP_TOOLS_*` flags. **All groups are on by default**, so the full tool set is available out of
the box. Turning groups off shrinks the `tools/list` manifest, which matters for small-context
(local) models that would otherwise be flooded with tool schemas before the first call.

| Group | Flag | Default | Tools |
|---|---|---|---|
| Metadata | `JDBC_MCP_TOOLS_METADATA` | **on** | `listSchemas`, `listTables`, `describeTable`, `getTriggerDefinition`, `getViewDefinition`, `listRoutines`, `getRoutineDefinition`, `listSequences`, `searchObjects` |
| Query | `JDBC_MCP_TOOLS_QUERY` | **on** | `executeQuery` |
| Admin | `JDBC_MCP_TOOLS_ADMIN` | **on** | `rebuildCatalog` |
| Sample | `JDBC_MCP_TOOLS_SAMPLE` | **on** | `sampleRows` |
| Query analysis | `JDBC_MCP_TOOLS_ANALYSIS` | **on** | `explainQuery`, `analyzePlan`, `validateQuery`, `inspectQuery`, `queryLint`, `resolveQueryLineage` |
| Distribution | `JDBC_MCP_TOOLS_DISTRIBUTION` | **on** | `columnStats`, `columnDistribution`, `columnHistogram`, `nullRatio`, `estimateSelectivity`, `joinCardinality` |
| Statistics | `JDBC_MCP_TOOLS_STATS` | **on** | `tableStats`, `indexStats`, `unusedIndexes`, `redundantIndexes`, `fkIndexCoverage` |
| Benchmark | `JDBC_MCP_TOOLS_BENCHMARK` | **on** | `benchmarkQuery`, `timedQuery` |
| Usage catalog | `JDBC_MCP_TOOLS_USAGE` | **on** | `usageCatalogStatus`, `getQuery`, `listQueries`, `findQueriesByTable`, `findQueriesByColumn`, `observedRelationships`, `listKnownTags`, `listKnownDomains`, `listKnownKinds`, `invalidateUsageCatalogCache` |
| Schema context | `JDBC_MCP_TOOLS_SCHEMA_CONTEXT` | **on** | `tableContext`, `findJoinPaths`, `schemaLint`, `schemaBrief`, `schemaGraph`, `queryContext`, `schemaGraphDot` |
| Connections | `JDBC_MCP_TOOLS_CONNECTIONS` | **on** | `listConnections` |

Each flag accepts `true` / `false`. For a small-context local model, turn off the groups you do not
need — for example keep only Metadata + Query by setting the rest to `false` — to cut the manifest
down to a minimal "explore the schema and run a query" set. The sections below describe each tool
regardless of its group.

### Query

| Tool | Description |
|---|---|
| `executeQuery` | Execute a `SELECT`, `WITH`, or `EXPLAIN` statement. Parameters: `sql`, `params` (array for `?`) or `namedParams` (object for `:name`), `limit`, `timeoutSeconds`. The result is marked with `truncated: true` if the row limit is hit |
| `explainQuery` | Return the execution plan. PostgreSQL: `EXPLAIN (FORMAT TEXT)`. Oracle: `EXPLAIN PLAN FOR` plus `DBMS_XPLAN.DISPLAY`. SQL Server: `SET SHOWPLAN_TEXT ON` on the same session. Parameters can be passed as `params` (`?`) or `namedParams` (`:name`). `analyze=true` on PostgreSQL enables `EXPLAIN ANALYZE`; be careful, because the query is actually executed. SQL Server currently returns estimated plans only |
| `analyzePlan` | Compact LLM-oriented plan summary instead of a large raw plan dump: highest-cost nodes, full scans on large tables, estimate errors (planner vs. reality, requires `analyze=true` on PostgreSQL), risky nested loops with large outer input, and disk sort spills. PostgreSQL: `EXPLAIN (FORMAT JSON)` / `EXPLAIN ANALYZE`. Oracle: `EXPLAIN PLAN` plus `PLAN_TABLE` (`analyze` is ignored because Oracle provides a static plan here). SQL Server: `SET SHOWPLAN_XML ON` estimated plan. Parameters can be passed as `params` (`?`) or `namedParams` (`:name`) |
| `validateQuery` | Validate syntax without execution: read-only guard plus driver `prepareStatement`, with a JSqlParser-derived `inspection` summary when parsing succeeds. Parameters can be passed as `params` (`?`) or `namedParams` (`:name`). Useful for LLM self-correction |
| `inspectQuery` | Parse SQL through JSqlParser without touching the database and return an AST summary: tables, aliases, CTEs, select items, joins, predicates, order by, columns, parameters, features, and parser warnings |
| `queryLint` | Parse SQL and combine the AST with metadata, index, and FK checks. Returns advisory warnings such as unknown tables or columns, `SELECT *`, joins without conditions, FKs without supporting indexes, and predicate/order-by columns that are not leading index columns. SQL is not executed |
| `resolveQueryLineage` | Resolve direct objects referenced by a query and recursively expand database views/materialized views to underlying physical tables. Function/procedure expansion is best-effort: embedded `SELECT` / `WITH` statements are extracted from routine source when available. Parameters: `sql`, `schema`, `expandViews`, `expandRoutines`, `maxDepth` |

### Benchmarking

Tools for measuring the real cost of a query, so the LLM does not have to guess from the plan and
can see actual milliseconds and buffer counters.

| Tool | Description |
|---|---|
| `benchmarkQuery` | Run the query `coldRuns + warmRuns` times (defaults to 1 cold + 3 warm) and return wall-clock `min`, `median`, and `max` for warm runs; cold runs are reported separately. Parameters can be passed as `params` (`?`) or `namedParams` (`:name`). `limit` and `timeoutSeconds` are **required**; unbounded queries are rejected. Returns the size of the last result (`row_count`, columns, `truncated`), not the rows |
| `timedQuery` | Regular `executeQuery` plus wall-clock `elapsed_ms`. Parameters can be passed as `params` (`?`) or `namedParams` (`:name`). On PostgreSQL, it also captures `pg_stat_statements` snapshots before and after the query; the diff shows which query IDs added `calls`, `total_exec_time_ms`, `rows`, `shared_blks_hit`, and `shared_blks_read`, making it clear where the server spent time. Requires `pg_stat_statements` (`CREATE EXTENSION pg_stat_statements;` plus `shared_preload_libraries`); if the extension is missing, returns `pg_stat_statements.available: false` |

### Metadata

| Tool | Description |
|---|---|
| `listSchemas` | List schemas. System schemas are hidden by default; use `includeSystem=true` to show all |
| `listTables` | List tables and views in a schema. Parameters: `schema`, `namePattern` (with `%` / `_`), `types` (comma-separated, for example `TABLE,VIEW,MATERIALIZED VIEW`) |
| `describeTable` | Full object description in one call: columns, primary key, unique constraints, indexes, outgoing/incoming FKs, CHECK constraints and allowed values, plus compact trigger metadata |
| `getTriggerDefinition` | Trigger body for one named trigger. Parameters: `schema`, `table`, `trigger` |
| `getViewDefinition` | SQL definition of a view |
| `listRoutines` | Functions, procedures, and packages in a schema |
| `getRoutineDefinition` | Function or procedure source code. On Oracle, all `ALL_SOURCE` lines are concatenated in order |
| `listSequences` | Sequences in one schema, or across schemas when `schema` is omitted |
| `searchObjects` | Case-insensitive substring search across non-system tables, views, routines, sequences, and synonyms |

### Schema Context

High-level tools for quick schema orientation and SQL authoring. Instead of manually calling
`listTables` -> `describeTable` -> `sampleRows` for each table, an LLM can get ready-to-use
context in one call: tables, columns, relationships, constraints, and sample rows.

| Tool | Description |
|---|---|
| `tableContext` | Context around one table: the table itself, FK parents, and optionally child tables and relationship edges. FK traversal uses the requested depth (default 1, max 4). Parameters: `schema`, `table`, `depth`, `includeIncoming`, `includeStats`, `includeObserved` |
| `findJoinPaths` | Find JOIN paths between two tables through FKs. The graph is traversed in both directions and each edge includes `joinCondition` and a typed `evidence` bundle (see *Edge evidence* below). Parameters: `fromSchema` / `fromTable`, `toSchema` / `toTable`, `maxDepth` (default/max 4), `maxPaths` (default 5, max 25), `scanLimit` (default/max 300), `includeObserved` |
| `schemaBrief` | Plain-text full-schema map for SQL authoring: all matching tables/views with column counts, PK, incoming/outgoing relationship counts, key-like columns, central/isolated tables, and capped key FK relationships. Use this first when relevant tables are unknown; follow with `queryContext` for detailed context. Parameters: `schema`, `terms` (optional substring search), `maxTables` (safety cap; default 2000, max 5000) |
| `schemaGraph` | Schema relationship graph metrics: nodes with in/out degree and classification, edges, central tables, isolated tables, connected components, and cycle hints. Optionally includes the shortest path between two tables |
| `schemaLint` | Schema lint audit: missing primary keys, FKs without indexes, FK type mismatches, nullable unique constraints, status/type columns without CHECK constraints, orphan `*_id` columns, missing remarks, isolated tables, and wide tables. Checks are configurable through `checks` |
| `queryContext` | Build compact SQL-authoring context from search terms and/or explicit tables. Finds relevant tables and columns using declared schema names/comments plus usage-catalog semantic evidence when available, includes constraints and allowed values, relationships and JOIN paths between selected tables, and optionally sample rows (up to 3 per table) |
| `schemaGraphDot` | DOT/Graphviz representation of the schema relationship graph. Nodes are tables with all columns and types (`PK` and `FK` marked inline), edges include JOIN conditions. Parameters: `schema`, `tables` (optional comma-separated filter) |

#### Edge evidence

When `includeObserved` is left unset, `tableContext` / `findJoinPaths` enable
it automatically if the local usage catalog is enabled (see *Usage Catalog* below). Every
relationship edge then carries a typed three-layer `evidence` bundle. Each layer is independently
optional and is omitted when there is no signal:

- `declaredSchema` — the relationship is a declared foreign key in the database catalog. Carries
  the FK name and column lists.
- `observedQuery` — the equi-join pair appears in stored application queries. Carries
  `joinSupport` (number of distinct queries) and `queryUids` (up to 5 contributing uids).
- `semanticUsage` — terms shared across queries that touch *both* tables: business domains,
  business objects, and output labels, plus the co-occurring query count and uid preview. This
  layer decorates existing edges only — it never proposes new relationships.

```jsonc
{
  "relationshipType": "foreignKey",
  "fromTable": "ORDERS", "fromColumns": ["CUSTOMER_ID"],
  "toTable": "CUSTOMERS", "toColumns": ["ID"],
  "evidence": {
    "declaredSchema": { "foreignKeyName": "FK_ORDERS_CUSTOMER", "fromColumns": ["CUSTOMER_ID"], "toColumns": ["ID"] },
    "observedQuery": { "joinSupport": 18, "queryUids": ["SHOP/InvoiceReport.json#header"] },
    "semanticUsage": {
      "sharedBusinessDomains": [{ "value": "Customers", "support": 12, "queryUids": [...] }],
      "sharedBusinessObjects": [{ "value": "Invoice payer", "support": 4, "queryUids": [...] }],
      "sharedOutputLabels":     [{ "value": "Payer name",   "support": 3, "queryUids": [...] }],
      "coOccurringQueryCount": 22,
      "coOccurringQueryUids": [...]
    }
  }
}
```

Equi-join pairs seen only in stored queries (no declared FK) are appended as new edges with
`relationshipType: "observed"` and `undirected: true`, between tables already in scope. Composite
(multi-column) FKs receive a `declaredSchema` layer but no observed-pair match in this iteration.
`schemaBrief`, `schemaGraph`, and `queryContext` only surface declared FK relationships.

#### Evidence model

The schema-context layer keeps three sources of knowledge separate:

- `declared_schema` - live database introspection: tables, columns, PK/FK, indexes, constraints,
  comments and statistics.
- `observed_query` - the indexed query catalog: which stored application/report queries reference
  a table or column, and in which SQL context (`select`, `where`, `join`, `order_by`, `having`).
- `semantic_usage` - adapter-supplied business meaning: query domains/tags/labels, output labels,
  parameter descriptions, field usages, rendered business objects and confidence.

In `tableContext`, the existing table fields are the compact `declared_schema` view. When
`includeObserved` is enabled and the usage catalog is available, each table also gets an
`evidence` block:

```json
{
  "evidence": {
    "observedQuery": {
      "queryCount": 12,
      "queryUids": ["SHOP/reports/customer-card#main"],
      "columns": [
        {"column": "STATUS", "queryCount": 5, "contexts": [{"value": "where", "support": 4}]}
      ]
    },
    "semanticUsage": {
      "businessDomains": [{"value": "Customers", "support": 8}],
      "businessTags": [{"value": "customer", "support": 6}],
      "queryLabels": [{"value": "Customer card", "support": 3}],
      "outputLabels": [{"value": "Customer name", "support": 4}],
      "businessObjects": [{"value": "Customer card", "support": 3}]
    }
  }
}
```

The server treats this as evidence, not as a single canonical business model. Different queries
may legitimately attach different business roles to the same physical table or column.

`queryContext` also uses `semantic_usage` as a discovery signal. When the user passes natural
language `terms`, the server searches usage-catalog domains, tags, query labels, output labels and
business objects. Matching tables are returned in `semanticMatches` and are considered before the
fallback name/comment scan over live schema metadata. This lets terms such as "payer" find a
physical `CUSTOMERS` table when existing reports expose `customers.name` as "Payer name".

### Usage Catalog

A catalog of *known* SQL usage against the inspected database, together with optional
**business context**: parameters with descriptions, output columns with their meaning, and where
each output is displayed in the consuming artifact (Excel cell in a BI Publisher report,
dashboard widget, etc.).

There are two sources. File-backed usage comes from directories / JSON files / zip archives
containing canonical QueryUsage JSON records. Database-native usage is derived automatically from
the connected schema's views, routines and triggers. At runtime the server parses these records and
builds a persistent SQLite index with extracted tables / columns / equi-join pairs as facts. JSON
files remain authoritative for file-backed records; native records are refreshed from live metadata.

**Purpose.** The metadata tools answer "what tables and columns exist"; the usage catalog answers
"how are they used by applications". It supports lookups such as "which reports reference this
column" and "which business label does this output field carry", and it feeds the `observedQuery`
and `semanticUsage` layers of the relationship `evidence` bundle, so equi-joins seen in stored
queries appear next to declared foreign keys (for example, "these two columns are joined in 17
stored report queries", with their uids).

**Identity.** Each query is keyed by `(source.kind, source.path, source.unit)`. Diagnostics and
evidence render this key as:

```
{source.kind}/{source.path}#{source.unit}
```

The `#unit` suffix is omitted when there is no unit. Examples:

```
bi-publisher-report/reports/customers/CustomerCard.xdo#CUST
manual/manual/ad-hoc-2026-05-01
java-dao/src/main/java/com/example/shop/OrderDao.java#findByCustomer
```

`source.kind` and `source.unit` must not contain `/` or `#`; `source.path` must not contain `#`.
For duplicate source keys, the first record wins for that index build.

**Where the files live.** The default catalog directory is
`<data-dir>/<connection>/usage-catalog`. Configure `usageCatalogPaths` on the connection as a list
of additional directories, `.json` files, or `.zip` archives. Directories are scanned recursively
for `*.json`; zip archives are scanned for JSON entries. Set `usageCatalogEnabled: false` to
disable the catalog. `usageCatalogStatus` then reports `catalogEnabled: false`; other public usage
tools return an `argument` error explaining how to enable it.

**Database-native usage.** The catalog also indexes supported database objects from the default
schema:

- views / materialized views as `source.kind="database-view"` or
  `source.kind="database-materialized-view"`;
- functions and procedures as `source.kind="database-function"` /
  `source.kind="database-procedure"` where the engine reports that distinction;
- triggers as `source.kind="database-trigger"`.

Views usually contribute fully parsed table, column and join evidence. Routine and trigger bodies
are engine-specific, so the indexer first uses an ANTLR-based procedural pre-extractor to find
embedded `SELECT` / `WITH` / `INSERT` / `UPDATE` / `DELETE` / `MERGE` statements, then feeds those
statements into the existing JSqlParser analysis pipeline. If no embedded statement is found, the
object is still kept as a provenance record. Use `usageNativeSchemas` on the connection to
scan explicit schemas.

**Persistent index.** The server never builds the usage index on startup. The first usage-catalog
lookup builds it synchronously from file-backed records and database-native objects into the local
SQLite `<catalog>.db`. Source files and database objects remain authoritative. Use
`invalidateUsageCatalogCache` after changing them; it clears the indexed usage rows and the next
lookup rebuilds them.

**Local-only writes.** The usage catalog never writes to the inspected JDBC database. The existing `ReadOnlyGuard` and connection-level protections
remain in force.

**Typed payload.** The canonical `source`, `parameters[]`, `outputs[]`, `fieldUsages[]` and nested
objects are described by the JSON Schema (field names, types, descriptions, enum values). The same
record types (`QueryUsage` and friends in `usage/format/`) are used by file indexing.

The canonical source-agnostic JSON format is documented in
`docs/usage-catalog-format.md`; its JSON Schema lives at
`src/main/resources/schemas/query-usage-record.schema.json`, with examples under
`examples/usage/`. Source-specific adapters should emit this canonical shape rather than being
implemented inside the JDBC MCP server.

| Tool | Description |
|---|---|
| `usageCatalogStatus` | Current catalog state (`not_started`, `indexing`, `ready`, `failed`, or `invalidated`), enabled flag, and configured sources |
| `invalidateUsageCatalogCache` | Drop the runtime index. The next lookup rebuilds it synchronously from configured files and database-native objects |
| `getQuery` | Full record selected by `sourceKind`, `sourcePath`, and optional `sourceUnit`: header, parameters, parsed tables/columns/join pairs, outputs, and field usages |
| `listQueries` | Paginated listing with optional filters: `sourcePath` (LIKE — `%` / `_` allowed), `sourceKind`, `businessDomain`, `tag`, `parseStatus`, `searchText`, `limit`, `offset` |
| `findQueriesByTable` | All catalog queries that reference a given table. Case-insensitive matching against alias-resolved, uppercased table names. Optional `schema` filter |
| `findQueriesByColumn` | All catalog queries that reference a given column, with the SQL `context` of the reference (`select` / `where` / `join` / `order_by` / `having`). Optional `schema` and `table` filters |
| `observedRelationships` | Aggregate observed equi-join pairs across stored queries, grouped by `(left_table.left_column = right_table.right_column)` with `support` count and contributing query uids. Non-equi joins (BETWEEN, function-based) are excluded. The same data feeds the `observedQuery` layer of the relationship `evidence` bundle in `tableContext` / `findJoinPaths` |
| `listKnownTags` | Tags currently used in the catalog, with query counts. Lets the agent reuse a stable vocabulary across ingest calls |
| `listKnownDomains` | Same for `businessDomain` values |
| `listKnownKinds` | Source-kinds currently used in the catalog with their query counts. Helps the agent discover valid values for `listQueries` `sourceKind` filter |

**Resolution.** During indexing, table / column qualifiers are resolved cheaply through the
parser's alias map and uppercased for case-insensitive matching. An explicit schema in the SQL
(`SCHEMA.TABLE`) is preserved verbatim. Unqualified table references are resolved as part of the
index build against the live JDBC schema: exactly one match fills the schema, multiple matches are
marked `ambiguous`, and zero matches stay `unresolved`.

### Catalog Administration

| Tool | Description |
|---|---|
| `rebuildCatalog` | Rebuild the persistent structure snapshot and usage index for comma-separated `schemas` (or the configured/default scope), checkpoint SQLite WAL, and return the distributable `<catalog>.db` path and the connection it was built for |

This tool writes only to the local catalog. It does not modify the inspected database.

### Connections

| Tool | Description |
|---|---|
| `listConnections` | List the databases this server serves: `name` (the value to pass as `connection`), `description`, engine kind, default schema, whether a local catalog file already exists, and whether the pool has been built in this process |

`listConnections` reads configuration and the local filesystem only — it opens no database
connection, so it still answers when some of the configured databases are down. In an unfamiliar
installation it is the first call worth making.

### Persistent Structure Snapshot

Structural metadata (columns, keys, indexes, FKs, views, routines, triggers, sequences) is held in a
**persistent structure snapshot** stored in the local SQLite `<catalog>.db` file (the same database
file as the usage catalog, under `<data-dir>/<catalog>/`). SQLite runs in WAL mode, so Codex,
Claude, and other local agent processes can use the same catalog concurrently. This speeds up repeated calls to
`tableContext`, `findJoinPaths`, `schemaLint`, `schemaGraph`, `queryContext`, `describeTable`,
`searchObjects`, and the usage-catalog re-resolver. Statistics tools such as `tableStats`,
`indexStats`, `columnStats`, and `sampleRows` are **not** cached; their counters are live.

The snapshot is authoritative ("cache forever") — there is no TTL or staleness detection. It is
filled lazily (`describeTable` persists each table it loads) and can be front-loaded for whole
schemas with the `rebuildCatalog` tool, which builds the structure snapshot **and** the usage index
into one distributable `<catalog>.db`. `rebuildCatalog` checkpoints the WAL before returning.
Clear the catalog while all server processes are stopped by deleting `<catalog>.db` and any
adjacent `<catalog>.db-wal` / `<catalog>.db-shm` files.

Existing H2 `<catalog>.mv.db` files are not converted or deleted. On first SQLite startup the
server creates a new `<catalog>.db`, logs a warning, and leaves the legacy file untouched; run
`rebuildCatalog` to populate the new catalog.

Configuration:

- `structureSnapshotSchemas` - schemas to front-load on a full rebuild (empty → the default schema).
- `structureSnapshotOracleColumnQueryTimeoutSeconds` - Oracle-only timeout for the
  `DBMS_XMLGEN`-backed bulk column/default query during a full rebuild (default `300`; `0` disables).

Both are per-connection fields in [`connections.json`](#connection-fields).

### Data Exploration

| Tool | Description |
|---|---|
| `sampleRows` | Return a few rows from a table or view (`LIMIT` / `FETCH FIRST` / `TOP` depending on database). Parameters: `schema`, `table`, `limit` (default 10, max 100) |

### Selectivity and Distribution

| Tool | Description |
|---|---|
| `columnStats` | Basic column statistics: `total_rows`, `non_null_rows`, `distinct_values`, `min`, `max`. A cheap one-shot aggregate when only extremes are needed |

`columnStats` only reports extremes. The other tools answer "how selective is this predicate?"
and "how skewed are values in this column?", which is the information an LLM needs to choose an
index or rewrite a JOIN meaningfully.

| Tool | Description |
|---|---|
| `columnDistribution` | Top-N most frequent values of a column plus their share. Surfaces skew, for example `70%` of rows with `status='OK'`, where an index on `status` alone is not useful. Parameters: `schema`, `table`, `column`, `topN` (default 20, max 1000) |
| `columnHistogram` | Percentiles P25 / P50 / P75 / P90 / P95 / P99 plus `min`, `max`, and null count. Uses SQL:2003 `WITHIN GROUP`: `percentile_cont` for numeric types and `percentile_disc` for all others, including dates, timestamps, and text |
| `nullRatio` | One scan for null / non-null counts across every table column. Columns are sorted by descending `null_ratio`. `sparse=true` marks columns where more than 50% of rows are null, which may be candidates for a partial index |
| `estimateSelectivity` | Estimate how many rows a predicate would return **without executing the query**, using `EXPLAIN` on `SELECT 1 FROM t WHERE <predicate>`. Returns estimated rows, baseline row count without the filter, and selectivity. Useful for placing the most selective predicate first in a composite index |
| `joinCardinality` | Estimate the output row count of a `JOIN` without executing it. Returns planner estimate, per-side row counts, and `selectivity_vs_cartesian`. Supports `INNER`, `LEFT`, `RIGHT`, and `FULL` |

### Object Statistics

These tools give the LLM object scale and health signals; without that, optimization advice becomes
guesswork. Data comes from system catalogs (`pg_class`, `pg_stat_*`, `ALL_TABLES`, `ALL_INDEXES`,
`DBA_SEGMENTS`) and is aggregated on the Java side.

| Tool | Description |
|---|---|
| `tableStats` | Table and index sizes in bytes, estimated row count, dead tuples on PostgreSQL, last vacuum/analyze, and seq/idx scan counters. On Oracle, also includes best-effort `DBA_SEGMENTS` data when available |
| `indexStats` | Per-index size, scan counter, columns, unique/primary flag, and index type. PostgreSQL extras: `idx_tup_read/fetch`, `pg_get_indexdef`. Oracle extras: `distinct_keys`, `clustering_factor`, `blevel`, `leaf_blocks`, `last_analyzed` |
| `unusedIndexes` | Indexes with zero scans on PostgreSQL (`pg_stat_user_indexes`). PK and UNIQUE indexes are excluded. On Oracle, returns a diagnostic note because `ALL_INDEXES` does not expose usage counters; `DBA_INDEX_USAGE` 12.2+ or `V$OBJECT_USAGE` with `ALTER INDEX ... MONITORING USAGE` is needed |
| `redundantIndexes` | Indexes whose column list is a strict prefix of another index on the same table. Unique indexes are not reported because dropping them would remove a constraint. Index type must match |
| `fkIndexCoverage` | Foreign keys on the child side that lack a supporting index, a classic cause of slow `DELETE` / `UPDATE CASCADE` and slow JOINs. The result includes `suggested_index_columns` ready for `CREATE INDEX` |

All tools are **read-only**; data is not modified.

## MCP Resources

When `JDBC_MCP_RESOURCES_ENABLED=true`, the server exposes — for every configured connection that
already has a local catalog file — a concrete catalog manifest, one concrete resource for every table
or view already persisted in that connection's structure snapshot, and two parameterized resource
templates:

```text
jdbc-mcp://catalog/<catalog>/manifest
jdbc-mcp://catalog/<catalog>/schemas/SSV/tables/CUSTOMERS
jdbc-mcp://catalog/<catalog>/schemas/{schema}/tables/{table}
jdbc-mcp://catalog/<catalog>/schemas/{schema}/tables/{table}/columns/{column}
```

`<catalog>` is the percent-encoded connection name. It is fixed per connection when the server
starts and is not a
template variable clients can use to switch databases: a read resolves the connection from the URI it
was given. This keeps resource URIs unambiguous both across the connections of one server and across
several registered instances of this jar. The manifest reports database kind,
snapshot version/build time/covered schemas, and the exact templates for its catalog. Table and
column reads reuse `MetadataService`, so they have the same persistent-snapshot and live-fallback
semantics as `describeTable`.

Concrete table resources are loaded from the local SQLite snapshots when the MCP server starts — no
database is contacted and no JDBC pool is created for this — so clients with MCP resource-picker
support can offer entries like `SSV.CUSTOMERS` without querying the live database. Their compact descriptions contain the database comment when present, the primary-key
columns, and outgoing foreign-key mappings; column lists and counts are intentionally omitted. After
running `rebuildCatalog` in an already-running server, restart or reconnect that MCP server instance
to refresh its concrete resource list.

Column resources include the column definition plus matching PK position, unique constraints,
indexes, outgoing/incoming foreign keys, and CHECK constraints. URI path segments preserve case
and use UTF-8 percent encoding. Resources are disabled by default; enabling them leaves the MCP
tools unchanged.

## Error Format

All tools return errors in the same shape: JSON with `error` and `kind` fields.

```json
{"error": "Only SELECT / WITH / EXPLAIN statements are allowed", "kind": "rejected"}
```

| `kind` | When |
|---|---|
| `sql` | The database returned a `SQLException` for syntax, missing object, missing permission, and similar cases |
| `argument` | Invalid tool argument |
| `unsupported` | The connection's engine cannot answer this at all (e.g. plans on a generic JDBC connection); retrying with other arguments will not help |
| `rejected` | The read-only guard blocked the query before it reached the database |
| `not_found` | `getViewDefinition`, `getRoutineDefinition`, or `getTriggerDefinition` found nothing. The response body also includes `missing` and `name` |
| `driver` / `unexpected` / `plan_parse` | Internal driver failure, unhandled failure, or plan parsing failure |

`validateQuery` uses its own shape, without `kind`; `valid` is the discriminator.

```json
{"valid": true,  "parameters": 1, "columns": 3}
{"valid": false, "stage": "guard|params|driver", "error": "..."}
```

## Read-only Protection

Protection is layered and is designed primarily for accidental `DELETE` / `DROP` statements from
an LLM, not for a malicious actor. A malicious actor already has the database URL, username, and
password — which is also why the server
[keeps credentials out of the environment](#why-credentials-live-in-a-file-not-in-environment-variables),
so that an agent does not casually acquire them.

1. **ReadOnlyGuard in project code.** Before sending SQL to the database, the server first parses it with JSqlParser and checks the AST. Only a single `SELECT`, `WITH`, or `EXPLAIN` is allowed. Write CTEs, `SELECT INTO`, and locking clauses such as `FOR UPDATE` are forbidden. If JSqlParser cannot parse dialect-specific SQL, the guard falls back to the older lexical check: first meaningful token, multi-statement rejection, comment skipping, and write-keyword detection outside strings and quoted identifiers.
2. **`connection.setReadOnly(true)`.** Set by Hikari and again by this server on each checkout.
3. **PostgreSQL: `default_transaction_read_only=on`.** Added to the JDBC URL automatically unless you already provided your own `options=`. Even server-side DDL is rejected.
4. **Oracle: JDBC read-only hint.** Oracle JDBC treats `setReadOnly(true)` mostly as an advisory hint. The client-side guard and a dedicated read-only database user are the primary Oracle protections. Oracle `EXPLAIN PLAN` writes a static plan to `PLAN_TABLE`; this server scopes those reads with a generated `STATEMENT_ID`.
5. **SQLite: the file is opened read-only.** `open_mode=1` is added to the URL unless you set `open_mode` yourself; SQLite then rejects every write.
6. **Firebird: server-enforced read-only transactions.** Jaybird turns `setReadOnly(true)` into read-only transactions, which the server enforces for DML and DDL alike.
7. **SQL Server: JDBC read-only hint plus SHOWPLAN estimated plans.** SQL Server also treats `setReadOnly(true)` as a hint. Use a least-privilege login/user for strong enforcement. `explainQuery` and `analyzePlan` use `SHOWPLAN_TEXT/XML`, which returns estimated plans without executing the statement.
8. **Generic JDBC: best-effort.** The guard, plus `setReadOnly(true)` where the driver honours it. Use a read-only URL option where the driver has one, or a read-only database user.

### Maximum Protection: Use a Read-only Database User

A dedicated user with read-only permissions keeps the database protected even when the guard is
disabled or bypassed.

**PostgreSQL:**

```sql
CREATE ROLE ai_readonly LOGIN PASSWORD 'strong-password';
GRANT CONNECT ON DATABASE mydb TO ai_readonly;
GRANT USAGE ON SCHEMA public TO ai_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ai_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO ai_readonly;
```

**Oracle:**

```sql
CREATE USER ai_readonly IDENTIFIED BY "strong-password";
GRANT CREATE SESSION TO ai_readonly;
GRANT SELECT ANY DICTIONARY TO ai_readonly;  -- for metadata
-- For each required table/view:
GRANT SELECT ON app_schema.customers TO ai_readonly;
-- ...or a role collecting all SELECT grants:
-- CREATE ROLE ai_ro_role; GRANT ai_ro_role TO ai_readonly;
```

**SQL Server:**

```sql
CREATE LOGIN ai_readonly WITH PASSWORD = 'strong-password';
CREATE USER ai_readonly FOR LOGIN ai_readonly;
GRANT SELECT ON SCHEMA::dbo TO ai_readonly;
GRANT VIEW DEFINITION TO ai_readonly; -- for object definitions and richer metadata
GRANT SHOWPLAN TO ai_readonly;        -- for explainQuery/analyzePlan estimated plans
```

### Disabling the Guard

If you need to call, for example, a stored procedure with read-only semantics that the guard does
not allow, you can disable client-side validation:

```json
"readonlyGuard": "off"
```

Connection-level protections (`setReadOnly` and, on PostgreSQL, `default_transaction_read_only`)
remain enabled. On Oracle and SQL Server, `setReadOnly` is best-effort; use a read-only database
user for the strongest guarantee.

## Server Environment Variables

Databases, credentials and everything that varies per database live in
[`connections.json`](#configuring-connections) — deliberately
[not in the environment](#why-credentials-live-in-a-file-not-in-environment-variables). The
environment configures only the server process itself:

| Variable | Required | Description |
|---|---|---|
| `JDBC_MCP_CONNECTIONS_FILE` | no | Path of the JSON file describing the named connections this server serves; default `<data-dir>/connections.json`. A missing or empty file starts the server with no connections (warning logged); a malformed one is a startup error |
| `JDBC_MCP_DATA_DIR` | no | Root directory for server-local data, default `~/.jdbc-mcp-server`. Each connection gets its own subdirectory under it |
| `JDBC_MCP_RESOURCES_ENABLED` | no | Expose the catalog-qualified manifest plus concrete table resources and table/column resource templates; default `false` |
| `JDBC_MCP_TOOLS_*` | no | Per-group tool toggles that control which tools appear in `tools/list`. All groups default to `true`; set a group to `false` to hide it (useful for small-context models). See [Tool Groups](#tool-groups) |

A connection's own settings — URL, credentials, default schema, timeouts, row caps, pool sizes, the
read-only guard, snapshot and usage options — are fields of its `connections.json` entry; see
[Connection fields](#connection-fields).

## Build

```bash
# Set JDK 21+ explicitly if it is not your default JDK:
export JAVA_HOME="$HOME/.jdks/jdk-21.0.6"

./gradlew build
```

Result: `build/libs/jdbc-mcp-server.jar` (includes PostgreSQL, Oracle, SQL Server, Firebird, and SQLite drivers).

### Integration Tests

Integration tests start real PostgreSQL, Oracle Free, SQL Server, and Firebird 3 instances through
Testcontainers, so Docker is required. They are excluded from the regular build and run separately
(the SQLite suites, including generic JDBC over an external SQLite driver, need no Docker and run
with `./gradlew test`):

```bash
./gradlew integrationTest
```

To run only the SQL Server Testcontainers suite:

```bash
./gradlew integrationTest --tests "*SqlServerIntegration*"
```

> The first Oracle Free and SQL Server runs download large images and may take several minutes to start.

### Smoke Tests Against a Real Oracle Database

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

## Project Structure

```text
+-- src/main/java/ru/it_spectrum/ai/jdbc/mcp/
|   +-- JdbcMcpServerApplication.java   - Spring Boot entry point
|   +-- config/
|   |   +-- JdbcProperties.java         - one connection's JDBC settings (URL, credentials, limits, pool)
|   |   +-- JdbcMcpProperties.java      - local data directory and catalog name
|   |   +-- UsageProperties.java        - usage-catalog sources and native-object settings
|   |   +-- StructureSnapshotProperties.java - schemas captured by rebuildCatalog
|   |   +-- DatabaseKind.java           - engine from `dialect` or the URL prefix; generic with driverPath
|   |   +-- DriverProperties.java       - dialect / driverPath / driverClass of one connection
|   |   +-- ExternalDriver.java         - loads driverPath jars in an isolated class loader
|   |   +-- DriverDataSource.java       - DataSource over an external driver, bypassing DriverManager
|   |   +-- DataSourceConfig.java       - Hikari pool builder + connection-level read-only mode
|   |   +-- ConnectionsConfig.java      - the connection registry bean
|   +-- connection/
|   |   +-- ConnectionsFile.java        - connections.json shape
|   |   +-- ConnectionsLoader.java      - connections.json + built-in defaults -> connection definitions
|   |   +-- EnvironmentPlaceholders.java - ${ENV_VAR} substitution
|   |   +-- ConnectionDefinition.java   - one named database and its effective settings
|   |   +-- ConnectionRegistry.java     - configured connections, lazily built, closed on shutdown
|   |   +-- ConnectionContext.java      - the service graph of one connection
|   |   +-- SpringConnectionContextFactory.java - builds it as a lazy child ApplicationContext
|   |   +-- ConnectionScopeConfig.java  - per-connection DataSource and DatabaseKind beans
|   +-- dialect/
|   |   +-- SqlDialect.java             - dialect interface
|   |   +-- PostgresDialect.java        - EXPLAIN, pg_catalog, pg_get_viewdef
|   |   +-- OracleDialect.java          - EXPLAIN PLAN, ALL_VIEWS, ALL_SOURCE, Oracle metadata queries
|   |   +-- SqlServerDialect.java       - SHOWPLAN, sys catalog metadata, SQL Server pagination
|   |   +-- FirebirdDialect.java        - RDB$ catalog queries, Jaybird plans, one logical schema
|   |   +-- SqliteDialect.java          - open_mode=1, schema main, sqlite_schema/pragma catalog, EXPLAIN QUERY PLAN
|   |   +-- GenericDialect.java         - any JDBC driver: DatabaseMetaData, portable SQL, traits read at runtime
|   |   +-- SchemalessConnections.java  - DatabaseMetaData view of a schemaless engine as one logical schema
|   |   +-- RankPercentiles.java        - window-function percentile_disc for Firebird and SQLite
|   |   +-- UnsupportedFeatureException.java - reported as error kind "unsupported"
|   |   +-- DialectConfig.java          - implementation selection by DatabaseKind
|   +-- sql/
|   |   +-- ReadOnlyGuard.java          - JSqlParser AST guard + lexical fallback
|   |   +-- SqlNotAllowedException.java
|   |   +-- QueryResult.java            - result shape
|   |   +-- SqlExecutor.java            - query execution with limits
|   |   +-- BenchmarkService.java       - benchmark (cold+warm) and timed (+ pg_stat_statements diff)
|   +-- metadata/
|   |   +-- MetadataService.java        - DatabaseMetaData + dialect-specific metadata
|   |   +-- SqliteStructureSnapshotStore.java - persistent SQLite structure snapshot
|   |   +-- StatsService.java           - table/index stats, FK coverage, redundant/unused indexes
|   |   +-- DistributionService.java    - column distribution / histogram / null ratio / selectivity / join cardinality
|   |   +-- SchemaContextService.java   - high-level schema context: overview, table context, join paths, graph, lint, brief, query context
|   +-- plan/
|   |   +-- ParsedPlan.java / PlanNode.java - unified engine-agnostic plan model
|   |   +-- PlanParser.java             - parser interface
|   |   +-- PostgresPlanParser.java     - JSON EXPLAIN -> tree
|   |   +-- OraclePlanParser.java       - PLAN_TABLE -> tree
|   |   +-- SqlServerPlanParser.java    - SHOWPLAN_XML -> tree
|   |   +-- FirebirdPlanParser.java     - Jaybird explained plan -> tree
|   |   +-- SqlitePlanParser.java       - EXPLAIN QUERY PLAN -> tree
|   |   +-- PlanAnalyzer.java           - summary: expensive / full scan / estimate error / nested loop / spill
|   +-- usage/
|   |   +-- CatalogDataSourceConfig.java - SQLite WAL datasource + schema init
|   |   +-- CatalogStorageService.java  - WAL checkpoint for distributable catalogs
|   |   +-- UsageCatalogService.java    - ingest, lookups, observed-relationships aggregation
|   |   +-- format/
|   |   |   +-- QueryUsage.java         - canonical query usage record DTO
|   +-- tools/
|       +-- QueryTools.java             - executeQuery
|       +-- QueryAnalysisTools.java     - explainQuery, analyzePlan, validateQuery, inspectQuery, queryLint, resolveQueryLineage
|       +-- MetadataTools.java          - schemas / tables / describe / view / routines / sequences / search
|       +-- AdminTools.java             - rebuildCatalog (build structure snapshot + usage index into a distributable <catalog>.db)
|       +-- SampleTools.java            - sampleRows
|       +-- DistributionTools.java      - columnStats, columnDistribution, columnHistogram, nullRatio, estimateSelectivity, joinCardinality
|       +-- StatsTools.java             - tableStats, indexStats, unusedIndexes, redundantIndexes, fkIndexCoverage
|       +-- BenchmarkTools.java         - benchmarkQuery, timedQuery
|       +-- SchemaContextTools.java     - schemaBrief, tableContext, findJoinPaths, schemaLint, schemaGraph, queryContext, schemaGraphDot
|       +-- ConnectionTools.java        - listConnections
|       +-- UsageTools.java             - usageCatalogStatus, invalidateUsageCatalogCache, getQuery, listQueries, findQueriesBy(Table|Column), observedRelationships, listKnownTags/Domains/Kinds
+-- src/main/resources/
    +-- application.yml                 - MCP stdio + server-level settings (data dir, tool groups)
    +-- usage-catalog-schema.sql        - DDL for the usage-catalog index (in <catalog>.db)
    +-- structure-snapshot-schema.sql   - DDL for the persistent structure snapshot (in <catalog>.db)
    +-- logback-spring.xml              - logs to stderr because stdout is used by MCP
```

## Stack

- Java 21, Spring Boot 4.0, Spring AI MCP 2.0.1 (`stdio` transport)
- HikariCP through Spring Boot `starter-jdbc`
- PostgreSQL JDBC 42.7.4
- Oracle JDBC `ojdbc11` 23.6.0.24.10
- Microsoft SQL Server JDBC 12.8.1
- Firebird JDBC (Jaybird) 6.0.6
- SQLite 3.51.3 (`sqlite-jdbc`): the WAL catalog (`<catalog>.db`) holding the usage index and persistent structure snapshot, and the driver for SQLite connections
- Gradle 9.3.1 with version catalog

## Troubleshooting

- **"Cannot find a Java installation ... matching languageVersion=21"** - install JDK 21+ and set `JAVA_HOME`. Gradle toolchains cannot download it without internet access.
- **Connection refused / ORA-01017 / FATAL / SQL Server login failed** - check the connection's `url`, `username`, and `password` in `connections.json`. For PostgreSQL, test the URL with `psql`; for Oracle, use `sqlplus user/password@...`; for SQL Server, test with `sqlcmd -S host,1433 -d database -U user -P password`.
- **`{"kind":"rejected","error":"Only SELECT / WITH / EXPLAIN statements are allowed"}`** - the guard worked. This is expected for any write operation. If the query is truly read-only, for example a read-only function call through `SELECT func(...)`, it will pass. For fully non-trivial cases, you can disable the guard with `"readonlyGuard": "off"` on that connection.
- **Oracle write attempt reached the database** - this should normally be blocked by the guard first. If `readonlyGuard` is `off`, rely on a read-only Oracle user; JDBC `setReadOnly(true)` is only a best-effort hint for Oracle.
- **Empty `describeTable` / `listTables` result on Oracle** - Oracle stores object names in uppercase. Pass `CUSTOMERS`, not `customers`.
- **Generic JDBC: "No driver in ... accepts the URL"** - the jars in `driverPath` register no driver for that URL prefix. Check the URL, or name the class with `driverClass`.
- **Generic JDBC: `kind: "unsupported"`** - the tool needs something JDBC does not expose portably (plans, view sources, sequences). See [Generic JDBC](#generic-jdbc) for what works.
- **Firebird: empty `describeTable`, or `argument` error "Firebird has no schemas"** - Firebird stores unquoted names in uppercase and has no schemas: pass `CUSTOMERS` and omit `schema` (or pass `PUBLIC`).
- **SQLite: "unable to open database file"** - the path in the URL does not exist (the read-only open never creates a file) or is not readable. Use an absolute path; on Windows forward slashes work: `jdbc:sqlite:C:/data/app.db`.
- **Firebird: "unsupported on-disk structure"** - the server version does not match the file's ODS (Firebird 3 reads ODS 12 only, Firebird 4/5 read ODS 13). Serve the file with the matching Firebird version, or back it up with `gbak` and restore it on a newer one.
- **SQL Server certificate errors** - set the JDBC URL encryption options explicitly, for example `encrypt=true;trustServerCertificate=false` with a trusted certificate, or `trustServerCertificate=true` only for local/dev use.
- **SQL Server `unusedIndexes` unsupported** - this tool intentionally avoids `sys.dm_db_index_usage_stats` because it usually requires elevated state-view permissions. Use `indexStats`, `fkIndexCoverage`, and `redundantIndexes` for low-privilege SQL Server audits.
- **The MCP client says the server failed to start** - run `java -jar jdbc-mcp-server.jar < /dev/null` in a terminal; a malformed `connections.json`, an invalid connection name or an unset `${VAR}` is reported there. See [Checking the configuration](#checking-the-configuration).
- **A setting in `connections.json` has no effect** - unknown keys are ignored without a warning, so check the spelling against [Connection fields](#connection-fields). The file is read only at startup: restart or reconnect the server after editing it.
- **A connection shows `configError` in `listConnections`** - the entry is unusable (unsupported URL without `driverPath`, unknown `dialect`, missing driver jar); the other connections keep working. [Configuration errors](docs/connections.md#configuration-errors) lists every message.

## License

This project is licensed under the Apache License, Version 2.0. See `LICENSE`.

Runtime and test dependencies are licensed by their respective owners. See
`THIRD_PARTY_NOTICES.md`, especially if you distribute a built fat jar containing bundled JDBC
drivers.
