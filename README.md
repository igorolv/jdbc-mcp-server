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
[Typical Scenarios](#typical-scenarios) ·
[Quickstart](#quickstart) ·
[Architecture](#architecture) ·
[MCP Tools](#mcp-tools) ·
[MCP Resources](#mcp-resources) ·
[Read-only Protection](#read-only-protection) ·
[Server Environment Variables](#server-environment-variables) ·
[Build](#build) ·
[Stack](#stack) ·
[Troubleshooting](#troubleshooting) ·
[License](#license)

## Features

- **Engines:** PostgreSQL 11+, Oracle 12c+, SQL Server 2012+, Firebird 3+, SQLite; generic JDBC
  via `driverPath`. Capabilities per engine: [Supported Databases](docs/databases.md).
- **Write protection:** JSqlParser AST guard (single `SELECT` / `WITH` / `EXPLAIN`), plus
  session-, transaction- or file-level read-only mode where the engine supports it. Details:
  [Read-only Protection](#read-only-protection).
- **Connections:** any number of databases in one [`connections.json`](docs/connections.md);
  every tool takes a `connection` argument; pools are created on first use. Credentials are not
  read from the environment.
- **Tools:** 49, in 11 [groups](#tool-groups) that can be disabled individually: metadata, query
  execution, plan analysis, column distribution and selectivity, table and index statistics,
  schema context, benchmarks, usage catalog. Reference: [MCP Tools](#mcp-tools).
- **Local catalog:** per-connection SQLite file with a persistent
  [structure snapshot](#persistent-structure-snapshot) and an index of known application queries
  ([usage catalog](#usage-catalog)).
- **Clients:** any MCP client with stdio transport; configuration examples for Claude Code, Codex
  CLI, OpenCode, VS Code, Copilot CLI, Cursor, Claude Desktop and Qwen Code in
  [docs/clients.md](docs/clients.md).

## Typical Scenarios

The agent picks the tools itself; these are the chains it usually follows. Every tool is described
in [MCP Tools](#mcp-tools).

| Scenario | Example request | Tools |
|---|---|---|
| Answer a data question | "How many orders did each region ship last month?" | `queryContext` or `schemaBrief` → `describeTable` → `findJoinPaths` → `validateQuery` → `executeQuery` |
| Explore an unfamiliar schema | "What does the billing schema hold, and how are its tables related?" | `schemaBrief` → `tableContext` → `sampleRows` → `schemaGraphDot` |
| Check a query before running it | "Is this report query correct, and which tables does it really read?" | `inspectQuery` → `queryLint` → `resolveQueryLineage` → `analyzePlan` |
| Speed up a slow query | "Why is this query slow, and which index would help?" | `analyzePlan` → `tableStats`, `indexStats` → `estimateSelectivity`, `columnDistribution`, `joinCardinality` → `benchmarkQuery` |
| Audit indexes and schema | "Find missing and redundant indexes in the orders schema." | `fkIndexCoverage`, `redundantIndexes`, `unusedIndexes`, `schemaLint` |
| Learn from existing application SQL | "How does the application usually join customers and invoices?" | `findQueriesByTable`, `findQueriesByColumn`, `observedRelationships` |

## Quickstart

**1. Get the jar** — download `jdbc-mcp-server.jar` from the
[latest release](https://github.com/igorolv/jdbc-mcp-server/releases/latest) (JDK 21+ required;
all JDBC drivers are bundled), or build it yourself:

```bash
./gradlew bootJar   # → build/libs/jdbc-mcp-server.jar
```

No local JDK? Use the Docker image `ghcr.io/igorolv/jdbc-mcp-server` instead — see
[Docker](docs/clients.md#docker).

**2. Describe your databases** in `~/.jdbc-mcp-server/connections.json`
(`%USERPROFILE%\.jdbc-mcp-server\connections.json` on Windows, `/data/connections.json` in the
Docker image) — one entry per database, keyed by the connection name:

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
    "archive": {
      "url": "jdbc:firebirdsql:embedded:/srv/data/archive-copy.fdb?nativeLibraryPath=/opt/firebird/lib",
      "username": "SYSDBA",
      "password": "secret",
      "description": "Copy of the warehouse archive, opened in-process (Firebird embedded)"
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

- `url` is the only required field; the engine is detected from its prefix. Any other URL needs a
  `driverPath` to a driver jar and is served as [generic JDBC](docs/databases.md#generic-jdbc).
- `description` is returned by `listConnections`, so an agent picks a database by meaning; name the
  stand and any restriction ("PRODUCTION — keep queries small").
- `archive` opens a local Firebird file in-process: `nativeLibraryPath` is the directory holding
  `libfbclient.so` / `fbclient.dll` with its engine and plugins. Point it at a copy of the file —
  see [Firebird](docs/databases.md#firebird).
- Use a [read-only database user](docs/read-only.md#maximum-protection-use-a-read-only-database-user): it is the only
  protection that does not depend on this server. Keep the file readable only by its owner.

Every field, naming rules, `${VAR}` secrets and several stands of one service:
[docs/connections.md](docs/connections.md).

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

OpenCode, VS Code with Copilot, Copilot CLI, Cursor, Claude Desktop, Qwen Code, and tips that apply
to every client: [docs/clients.md](docs/clients.md).

**4. Try it.** Ask the agent in plain words:

- *"Which databases can you reach?"* — it calls `listConnections` and lists the entries of your
  `connections.json` with their descriptions.
- *"How many tables are there in `orders`?"* — it calls `listTables` on that connection and counts
  them.

If the first call against a database fails, see
[Checking a configuration](docs/connections.md#checking-a-configuration).

## Architecture

```text
 MCP client (Claude Code, Codex, VS Code, Cursor, ...)
      |  JSON-RPC over stdin / stdout, no network port
      v
+- jdbc-mcp-server: one JVM, started by the client ------------------------------+
|                                                                                |
|  MCP layer          49 tools in switchable groups, optional resources          |
|      |              every call names its `connection`                          |
|      v                                                                         |
|  Connection registry  <-- connections.json, read once at startup               |
|      |              a connection is built on its first call, then reused       |
|      v                                                                         |
|  Per-connection context                                                        |
|   +- read-only guard (JSqlParser AST) -> SQL executor (row cap, timeout)       |
|   +- dialect: PostgreSQL, Oracle, SQL Server, Firebird, SQLite, generic        |
|   +- metadata, statistics, plan parser and analyzer                            |
|   +- local catalog <data-dir>/<name>/<name>.db (SQLite, WAL):                  |
|   |     structure snapshot + usage index of known queries                      |
|   +- Hikari pool of read-only JDBC connections -----------------> database     |
+--------------------------------------------------------------------------------+
```

- **One process, many databases.** The client starts the server as a child process and talks to it
  over stdio. The databases come from [`connections.json`](docs/connections.md); a connection's
  pool, local catalog and services are created only when a tool call first names it, so a database
  that is down or misconfigured affects only the calls made against it.
- **The SQL path.** Every statement goes through the read-only guard first, then runs on a
  read-only JDBC connection with the connection's row cap and timeout. Engine-level protections are
  described in [Read-only Protection](docs/read-only.md).
- **Dialects.** Each engine has its own implementation of metadata queries, plans and statistics;
  any other database is served in generic mode through `DatabaseMetaData` and portable SQL — see
  [Supported Databases](docs/databases.md).
- **Local catalog.** Structural metadata is persisted per connection in a SQLite file with no
  expiry: `describeTable`, the schema context tools and `searchObjects` read covered schemas from it
  and fall back to the live database otherwise. The same file holds the usage index. Live
  statistics, samples and plans are never cached. See
  [Persistent Structure Snapshot](#persistent-structure-snapshot) and [Usage Catalog](#usage-catalog).
- **Execution model.** Tool calls on one stdio session run sequentially. The MCP Java SDK 2.0.0 used
  by Spring AI 2.0.1 can lose responses when several concurrently executed tools finish at the same
  time, so the server keeps `immediateExecution(true)` until the SDK fixes this. A client's
  `notifications/cancelled` is not propagated to JDBC `Statement.cancel()`; the configured
  `queryTimeoutSeconds` (or a tool call's `timeoutSeconds` override) remains the server-side limit
  for a running SQL statement.

## MCP Tools

The 49 tools are grouped below by purpose.

Every tool takes `connection` as its **first, required** argument, naming the database to run
against — including installations that serve exactly one database. `listConnections` lists the
names. See [Several databases in one server](docs/connections.md#several-databases-and-environments).

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

Both are per-connection fields in [`connections.json`](docs/connections.md#connection-fields).

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

Besides tools, the server can publish the catalog as MCP resources, for clients that let the user
attach a table's description to the context. Resources are **off by default**; set
`JDBC_MCP_RESOURCES_ENABLED=true` to register them. The tools are the same either way.

Every usable connection publishes one resource and two resource templates:

| URI | Name | Content |
|---|---|---|
| `jdbc-mcp://catalog/<catalog>/manifest` | `<catalog>/manifest` | Database kind, the structure snapshot's version, build time and covered schemas, and the two templates below |
| `jdbc-mcp://catalog/<catalog>/schemas/{schema}/tables/{table}` | `<catalog>/table` | What `describeTable` returns: columns, keys, indexes, constraints, relationships, triggers |
| `jdbc-mcp://catalog/<catalog>/schemas/{schema}/tables/{table}/columns/{column}` | `<catalog>/column` | One column with its primary-key position, unique constraints, indexes, outgoing and incoming foreign keys, and CHECK constraints |

- **`<catalog>` is the connection name**, UTF-8 percent-encoded (`ssj@dev` becomes `ssj%40dev`). It
  is fixed per connection, not a template argument: a read resolves its connection from the URI, so
  a URI never points at the wrong database — across the connections of one server or across several
  registered instances. Schema, table and column segments are percent-encoded and keep their case.
- **Tables are not listed.** `resources/list` holds only the manifests; a schema with thousands of
  tables would otherwise turn it into a dump of the catalog. Clients discover names through
  `completion/complete` on the template arguments — `schema`, then `table` (given `schema`), then
  `column` (given both) — with case-insensitive prefix matches, at most 100 per response and
  `hasMore` when there are more.
- **Completions come from the local catalog** and never touch the database. Until a connection has
  a catalog they return nothing and the manifest reports snapshot version `0`; a catalog built with
  `rebuildCatalog` is picked up without a restart. Table and column reads work either way: like
  `describeTable`, they answer from the snapshot and fall back to the live database.
- **Metadata.** Every read carries `_meta` with `catalog`, `resourceSchemaVersion`,
  `snapshotVersion` and, once a catalog exists, `snapshotBuiltAt`.
- **Errors** use MCP error codes: a table or column that does not exist is `-32002` (resource not
  found, `data.uri` names the URI); a malformed URI is `-32602` (invalid params).

## Read-only Protection

The server never writes to the inspected database, and does not rely on one mechanism for that:

- a JSqlParser AST guard lets through a single `SELECT`, `WITH` or `EXPLAIN` and nothing else;
- every JDBC connection is read-only, and PostgreSQL, Firebird and SQLite enforce it inside the
  database (a read-only session or transaction, a file opened with `open_mode=1`);
- database credentials are kept in `connections.json`, never in the MCP client config or the
  environment, so an agent has no easy way around the server with `psql` or `sqlplus`.

The strongest guarantee is a read-only database user. The layers per engine, `GRANT` snippets for
PostgreSQL, Oracle and SQL Server, when the guard can be switched off, and why credentials live in a
file: [docs/read-only.md](docs/read-only.md). Tool errors, including the guard's `rejected`, are
listed in [docs/errors.md](docs/errors.md).

## Server Environment Variables

Databases, credentials and everything that varies per database live in
[`connections.json`](docs/connections.md) — deliberately
[not in the environment](docs/read-only.md#why-credentials-live-in-a-file-not-in-environment-variables). The
environment configures only the server process itself:

| Variable | Required | Description |
|---|---|---|
| `JDBC_MCP_CONNECTIONS_FILE` | no | Path of the JSON file describing the named connections this server serves; default `<data-dir>/connections.json`. A missing or empty file starts the server with no connections (warning logged); a malformed one is a startup error |
| `JDBC_MCP_DATA_DIR` | no | Root directory for server-local data, default `~/.jdbc-mcp-server`. Each connection gets its own subdirectory under it |
| `JDBC_MCP_RESOURCES_ENABLED` | no | Expose the catalog-qualified manifest and table/column resource templates with argument completion; default `false` |
| `JDBC_MCP_TOOLS_*` | no | Per-group tool toggles that control which tools appear in `tools/list`. All groups default to `true`; set a group to `false` to hide it (useful for small-context models). See [Tool Groups](#tool-groups) |

A connection's own settings — URL, credentials, default schema, timeouts, row caps, pool sizes, the
read-only guard, snapshot and usage options — are fields of its `connections.json` entry; see
[Connection fields](docs/connections.md#connection-fields).

## Build

```bash
# Set JDK 21+ explicitly if it is not your default JDK:
export JAVA_HOME="$HOME/.jdks/jdk-21.0.6"

./gradlew build
```

Result: `build/libs/jdbc-mcp-server.jar` (includes PostgreSQL, Oracle, SQL Server, Firebird, and SQLite drivers).

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
- **Generic JDBC: `kind: "unsupported"`** - the tool needs something JDBC does not expose portably (plans, view sources, sequences). See [Generic JDBC](docs/databases.md#generic-jdbc) for what works.
- **Firebird: `not_found` from `describeTable`, or `argument` error "Firebird has no schemas"** - Firebird stores unquoted names in uppercase and has no schemas: pass `CUSTOMERS` and omit `schema` (or pass `PUBLIC`).
- **SQLite: "unable to open database file"** - the path in the URL does not exist (the read-only open never creates a file) or is not readable. Use an absolute path; on Windows forward slashes work: `jdbc:sqlite:C:/data/app.db`.
- **Firebird: "unsupported on-disk structure"** - the server version does not match the file's ODS (Firebird 3 reads ODS 12 only, Firebird 4/5 read ODS 13). Serve the file with the matching Firebird version, or back it up with `gbak` and restore it on a newer one.
- **SQL Server certificate errors** - set the JDBC URL encryption options explicitly, for example `encrypt=true;trustServerCertificate=false` with a trusted certificate, or `trustServerCertificate=true` only for local/dev use.
- **SQL Server `unusedIndexes` unsupported** - this tool intentionally avoids `sys.dm_db_index_usage_stats` because it usually requires elevated state-view permissions. Use `indexStats`, `fkIndexCoverage`, and `redundantIndexes` for low-privilege SQL Server audits.
- **The MCP client says the server failed to start** - run `java -jar jdbc-mcp-server.jar < /dev/null` in a terminal; a malformed `connections.json`, an invalid connection name or an unset `${VAR}` is reported there. See [Checking the configuration](docs/connections.md#checking-a-configuration).
- **A setting in `connections.json` has no effect** - unknown keys are ignored without a warning, so check the spelling against [Connection fields](docs/connections.md#connection-fields). The file is read only at startup: restart or reconnect the server after editing it.
- **A connection shows `configError` in `listConnections`** - the entry is unusable (unsupported URL without `driverPath`, unknown `dialect`, missing driver jar); the other connections keep working. [Configuration errors](docs/connections.md#configuration-errors) lists every message.

## License

This project is licensed under the Apache License, Version 2.0. See `LICENSE`.

Runtime and test dependencies are licensed by their respective owners. See
`THIRD_PARTY_NOTICES.md`, especially if you distribute a built fat jar containing bundled JDBC
drivers.
