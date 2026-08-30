# JDBC MCP Server — Setup Guide for AI Agents

> **Build tool: Gradle (Kotlin DSL)**, not Maven. Use `./gradlew` (Linux/macOS) or
> `.\gradlew.bat` (Windows / PowerShell). There is no `pom.xml`, `mvnw`, or `mvn` — do not
> attempt to invoke them. Common commands:
> - Compile: `./gradlew classes`
> - Run tests: `./gradlew test` (single class: `./gradlew test --tests <FQCN>`)
> - Full build: `./gradlew build`

This is a local MCP server that provides read-only access to PostgreSQL, Oracle, and SQL Server databases.
It exposes 49 tools across ten groups. Tools may update the local SQLite catalog, but never the
inspected PostgreSQL, Oracle, or SQL Server database:

- **Query** — execute SELECT/WITH/EXPLAIN, validate without running, get plain or LLM-summarized plans.
- **Benchmark** — wall-clock cost of a query, optionally with `pg_stat_statements` deltas.
- **Metadata** — schemas, tables, columns, indexes, FKs, constraints, triggers, views, routines, sequences, object search.
- **Data exploration** — sample rows, basic column stats.
- **Selectivity / distribution** — top-N, percentiles, null ratios, planner-only predicate and join estimates.
- **Object statistics** — table/index size and activity, unused/redundant indexes, FK index coverage.
- **Schema context** — high-level snapshots, table neighborhoods, FK join paths, schema lint, ERD/DOT export.
- **Usage catalog** — indexed known SQL queries with business context, observed joins, and semantic evidence.
- **Catalog administration** — rebuild the persistent structure snapshot and usage index into a distributable SQLite catalog.
- **Connections** — list the databases this server serves and which one answers by default.

The server communicates over stdio (stdin/stdout). PostgreSQL, Oracle, and SQL Server JDBC drivers
are bundled inside the fat jar.

When explicitly enabled, the server exposes a catalog manifest and parameterized table/column MCP
resources in addition to tools. Every URI is namespaced by the connection name:

```text
jdbc-mcp://catalog/<catalog>/manifest
jdbc-mcp://catalog/<catalog>/schemas/{schema}/tables/{table}
jdbc-mcp://catalog/<catalog>/schemas/{schema}/tables/{table}/columns/{column}
```

The catalog segment is the connection name, UTF-8 percent-encoded and fixed per connection; it is not
a client-selectable template argument, and a read resolves the connection from the URI it was given.
Resources are published for every configured connection that already has a local catalog file. They
are disabled by default; set `JDBC_MCP_RESOURCES_ENABLED=true` to register them.

## Code style: typed response models

The project is gradually replacing ad-hoc `Map<String, Object>` response payloads with typed Java
`record` models under `src/main/java/ru/it_spectrum/ai/jdbc/mcp/model`.

When adding or changing code:

- Prefer typed `record` DTOs for service results and MCP response bodies.
- Keep tool classes thin: they should call services and serialize typed results via `JsonResponses`.
- Do not introduce new public service methods or model fields returning `Map<String, Object>` unless
  the payload is inherently dynamic.
- Acceptable dynamic cases are:
  - arbitrary SQL result rows, e.g. `QueryResult.rows()`;
  - user-provided SQL parameters, e.g. `namedParams`;
  - raw database-specific plan payloads / metadata, e.g. `PlanNode.raw()` and `ParsedPlan.meta()`;
  - local JDBC/JSON parser internals that are converted to typed records before crossing service
    boundaries.
- If a response shape is stable enough to document in AGENTS.md or README, it is stable enough to
  model as a `record`.

### `@Schema` annotations on response records

Every field of a record returned from an `@McpTool(generateOutputSchema = true)` method must
carry `nullable = true` **and** an explicit `requiredMode` — `NOT_REQUIRED` for optional fields,
`REQUIRED` only when the value is conceptually always present. Class-level `@Schema(description
= ...)` annotations stay as-is.

```java
@Schema(description = "...", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
String remarks,
@Schema(description = "...", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
int rowCount,
```

Why both flags are needed (this trips up everyone who skims one half of it):

- Spring AI's `McpJsonSchemaGenerator` uses `AbstractSpringAiSchemaModule.checkRequired`, which
  treats a `@Schema` without an explicit `requiredMode` as `AUTO` and emits the field into the
  schema's `required[]` array. Setting `nullable = true` alone does **not** remove the field
  from `required[]`.
- The autoconfigured `mcpServerJsonMapper` bean
  (`McpServerJsonMapperAutoConfiguration#mcpServerJsonMapper`) serialises responses with
  `JsonInclude.Include.NON_NULL` for both values and content, so any field that is `null` at
  runtime is **stripped from the wire JSON**.
- Combined, an optional field with default annotations produces a schema that says "required"
  while the actual JSON omits the key. The networknt validator running inside the MCP server
  then rejects the response with `MCP error -32602: ... must have required property 'X'`.

The fix on each axis:
- `requiredMode = NOT_REQUIRED` keeps the field out of `required[]`, so a stripped null is fine.
- `nullable = true` makes the JSON Schema type a union with `null`, so a backend that emits
  `"X": null` still validates.

`OutputSchemaSmokeTest` (`src/test/java/.../model/`) reproduces the failure path against a
faithful copy of the autoconfigured server mapper and locks the convention in. When you add a
new response record, run that test plus `ToolOutputSchemaSmokeTest`.

## Prerequisites

- JDK 21+ installed (check with `java -version`)
- A database account. A **read-only** database user is strongly recommended — see the README
  for SQL snippets to create one in PostgreSQL, Oracle, or SQL Server.

## Step 1: Write the connections file before running the server

Credentials are not required for `classes`, `test`, or `build`. Before starting the server or
running tests against a live database, ask the user for a JDBC URL, a username (preferably a
read-only user) and a password, then write `~/.jdbc-mcp-server/connections.json`:

```json
{
  "connections": {
    "myapp": {
      "url": "jdbc:postgresql://<host>:5432/<database>",
      "username": "ai_readonly",
      "password": "<pw>",
      "description": "What this database is for"
    }
  }
}
```

URL shapes: PostgreSQL `jdbc:postgresql://<host>:5432/<database>`, Oracle
`jdbc:oracle:thin:@//<host>:1521/<service>`, SQL Server
`jdbc:sqlserver://<host>:1433;databaseName=<database>`. The engine is detected from the prefix.

**This file is the only place a database is configured.** There are no `JDBC_URL` / `JDBC_USERNAME` /
`JDBC_PASSWORD` variables; startup fails when the file is missing or defines no connection. The
object key is the connection name, the name of the local catalog directory `<data-dir>/<name>/`
(`<name>.db`, `usage-catalog/`, `logs/`), and the value every tool call passes as `connection`.
It must match `[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?`, be at most 64 characters, and not be `.` or
`..`. The optional `@` names the two axes separately — `<service>@<stand>`, as in `ssj@dev` or
`ssj-ek-export@tst` — which a dash cannot do, because service names contain dashes already. In
resource URIs the `@` appears percent-encoded as `%40`; on disk the directory is the name as written.

Credentials are kept out of the environment on purpose: an agent that can read `JDBC_URL` and
`JDBC_PASSWORD` — from the MCP client config it edits, or from its own shell — can reach the database
with `psql` or `sqlplus` and bypass the read-only guard entirely. Write the password into the file
and `chmod 600` it. String values may reference an environment variable as `${VAR}`, but only use
that when the value comes from outside the agent's reach (a systemd unit, a wrapper script, a secret
manager) — never from the MCP client's `env` block, which defeats the purpose. See "Why credentials
live in a file, not in environment variables" in the README.

Per-connection fields, all optional, with their defaults: `defaultSchema` (session schema; Oracle
upper-cases, SQL Server usually `dbo`), `queryTimeoutSeconds` (30, `0` disables), `maxRows` (1000;
responses set `truncated: true` when hit), `fetchSize` (500), `readonlyGuard` (`strict`; `off`
disables the client-side check while connection-level read-only flags stay on, best-effort on Oracle
and SQL Server), `poolMaximumSize` (40), `poolMinimumIdle` (0), `poolConnectionTimeoutMs` (10000),
`poolValidationTimeoutMs` (5000), `poolIdleTimeoutMs` (60000), `structureSnapshotSchemas` (default
schema), `structureSnapshotOracleColumnQueryTimeoutSeconds` (300, `0` disables — Oracle's expensive
bulk column query during a rebuild, kept apart from `queryTimeoutSeconds`), and the `usage*` fields
listed under [Usage catalog tools](#usage-catalog-tools). See the README for the full table.

The structure snapshot persists structural metadata in the local SQLite `<catalog>.db` ("cache
forever", no TTL); live stats are not cached. SQLite WAL lets several local MCP processes share one
catalog. Build and checkpoint it with `rebuildCatalog`; clear it only while all processes are stopped.

Only two environment variables shape the server process itself:

- **Data directory** — `JDBC_MCP_DATA_DIR` (default `~/.jdbc-mcp-server`). Shared root for all
  server-local data; each connection gets its own subdirectory under it.
- **Connections file** — `JDBC_MCP_CONNECTIONS_FILE` (default `<data-dir>/connections.json`).

(Plus `JDBC_MCP_RESOURCES_ENABLED` and the `JDBC_MCP_TOOLS_*` group flags, which shape the manifest.)

## Step 2: Build

```bash
# If the default JDK is < 21, set JAVA_HOME explicitly, e.g.:
# export JAVA_HOME="$HOME/.jdks/jdk-21.0.6"

cd <path-to-this-project>
./gradlew build
```

The resulting jar: `build/libs/jdbc-mcp-server.jar` (all supported JDBC drivers bundled).

## Step 3: Verify the server starts

```bash
java -jar build/libs/jdbc-mcp-server.jar
```

The server talks MCP over stdio (no HTTP ports are opened). If it starts without errors it is
ready to be wired into an MCP client. Type `Ctrl-D` to stop.

## Step 4: Connect to an MCP client

Add the server to the client's MCP configuration:

```json
{
  "command": "java",
  "args": ["-jar", "<absolute-path-to>/jdbc-mcp-server.jar"],
  "env": {}
}
```

The databases come from `connections.json`, so there is nothing to put in `env` — add
`JDBC_MCP_CONNECTIONS_FILE` only if the file lives somewhere other than the default path.

**Where to put it:**

| Client | Config file | Server key |
|---|---|---|
| Claude Code | `~/.claude/settings.json` → `"mcpServers"` | `"jdbc"` |
| Qwen Code | `~/.qwen/settings.json` → `"mcpServers"` | `"jdbc"` |
| VS Code (Copilot/Continue) | `.vscode/mcp.json` → `"servers"` | `"jdbc"` |
| Cursor | `.cursor/mcp.json` → `"mcpServers"` | `"jdbc"` |
| Claude Desktop | `claude_desktop_config.json` → `"mcpServers"` | `"jdbc"` |

**Several databases in one server.** Instead of registering one instance per database, write
`~/.jdbc-mcp-server/connections.json` and register the server once — the manifest stays a single set
of tools, and each call picks its database with `connection`:

```json
{
  "connections": {
    "orders": {
      "url": "jdbc:postgresql://db.example.com:5432/orders",
      "username": "ai_readonly",
      "password": "secret",
      "defaultSchema": "public",
      "description": "Order service — customers, orders, shipments"
    },
    "billing": {
      "url": "jdbc:oracle:thin:@//oracle.example.com:1521/BILLING",
      "username": "AI_READONLY",
      "password": "${BILLING_DB_PASSWORD}",
      "description": "Legacy billing (Oracle)"
    }
  }
}
```

Keep the file readable only by its owner — it holds database credentials. The README documents
every per-connection field.

**Example for Claude Code (`~/.claude/settings.json`):**
```json
{
  "mcpServers": {
    "jdbc": {
      "command": "java",
      "args": ["-jar", "<absolute-path-to>/jdbc-mcp-server.jar"],
      "env": {}
    }
  }
}
```

After updating the config, restart the client so it picks up the new MCP server.

## Available tools

The server exposes **49 MCP tools**, all read-only with respect to the inspected database. Every tool
takes `connection` as its first, required argument — see
[Choosing a connection](#choosing-a-connection).
Which tools appear in `tools/list` depends on the `JDBC_MCP_TOOLS_*` group flags. **All groups are on
by default**, so the full set is available out of the box. To shrink the manifest for a small-context
(local) model, turn groups off — e.g. keep only Metadata + Query by setting the other flags to
`false` (`JDBC_MCP_TOOLS_ANALYSIS=false`, `JDBC_MCP_TOOLS_STATS=false`, …); see the README "Tool
Groups" table for the full mapping. The sections below document every tool.

### Query tools

| Tool | Description |
|---|---|
| `executeQuery` | Run a SELECT / WITH / EXPLAIN statement. Params: `sql`, `params` (array of values for `?` placeholders) or `namedParams` (object for `:name` placeholders), `limit`, `timeoutSeconds`. Result includes `truncated` flag if the row limit was hit |
| `explainQuery` | Return the execution plan. PostgreSQL: `EXPLAIN (FORMAT TEXT)`. Oracle: `EXPLAIN PLAN FOR` + `DBMS_XPLAN.DISPLAY`. SQL Server: `SET SHOWPLAN_TEXT ON` on the same session. `analyze=true` enables `EXPLAIN ANALYZE` on PostgreSQL (actually runs the query); Oracle and SQL Server return static/estimated plans. Params: `sql`, `params` (`?`) or `namedParams` (`:name`), `analyze` |
| `analyzePlan` | Compact, LLM-friendly summary of the execution plan: top-cost nodes, full scans on large relations, estimation errors (planner vs. reality — requires `analyze=true` on PG), risky nested loops with large outer input, disk sort spills. PostgreSQL: `EXPLAIN (FORMAT JSON)` / `EXPLAIN ANALYZE`. Oracle: `EXPLAIN PLAN` + `PLAN_TABLE` (static only, `analyze` ignored). SQL Server: `SET SHOWPLAN_XML ON` estimated plan. Params: `sql`, `params` (`?`) or `namedParams` (`:name`), `analyze` |
| `validateQuery` | Validate a statement without running it — read-only guard + driver-side `prepareStatement` plus a JSqlParser-derived `inspection` summary when possible. Params: `sql`, `params` (`?`) or `namedParams` (`:name`) |
| `inspectQuery` | Parse SQL with JSqlParser and return an AST-derived authoring summary without touching the database: tables, aliases, CTEs, select items, joins, predicates, order-by, referenced columns, parameters, features and parser-level warnings. Params: `sql` |
| `queryLint` | Parse SQL and combine the AST with metadata/index/FK checks. Returns advisory warnings such as unknown table/column, `SELECT *`, joins without conditions, missing FK indexes, and predicate/order-by columns that are not leading index columns. Does not execute SQL. Params: `sql`, `schema` |
| `resolveQueryLineage` | Resolve direct objects referenced by a query and recursively expand database views/materialized views to underlying physical tables. Function/procedure expansion is best-effort: embedded `SELECT` / `WITH` statements are extracted from routine source when available. Params: `sql`, `schema`, `expandViews`, `expandRoutines`, `maxDepth` |

### Benchmark tools

| Tool | Description |
|---|---|
| `benchmarkQuery` | Run the query `coldRuns + warmRuns` times (defaults 1 + 3) and report wall-clock min/median/max for the warm runs (cold runs reported separately). `limit` and `timeoutSeconds` are **required** — unbounded queries are rejected. Returns the size of the last result (row count, columns, truncated flag), not the rows. Params: `sql`, `params` / `namedParams`, `limit`, `timeoutSeconds`, `coldRuns`, `warmRuns` |
| `timedQuery` | Same shape as `executeQuery` plus `elapsed_ms` wall-clock. On PostgreSQL also attaches a before/after diff from `pg_stat_statements` (per `queryid`: added `calls`, `total_exec_time_ms`, `rows`, `shared_blks_hit/read`). Requires the extension; if missing returns `pg_stat_statements.available: false` |

### Metadata tools

| Tool | Description |
|---|---|
| `listSchemas` | List all schemas. System schemas (`pg_catalog`, `information_schema`, `SYS`, ...) are hidden unless `includeSystem=true` |
| `listTables` | List tables and views in a schema. Params: `schema`, `namePattern` (JDBC wildcards `%` / `_`), `types` (comma-separated, e.g. `TABLE,VIEW,MATERIALIZED VIEW`) |
| `describeTable` | Full table/view description in a single call: columns (name, type, size, nullable, default, remarks), primary key, unique constraints, indexes, outgoing foreign keys, tables referencing this one, constraints (with CHECK `definition`), allowed-values map (parsed from CHECK), triggers (compact: name, timing, events, enabled). Params: `schema`, `table` |
| `getTriggerDefinition` | Trigger body for one named trigger (the only piece not already returned by `describeTable`). Params: `schema`, `table`, `trigger` |
| `getViewDefinition` | Return the SQL definition of a view / materialized view. Params: `schema`, `name` |
| `listRoutines` | List functions, procedures, packages. Params: `schema`, `namePattern` |
| `getRoutineDefinition` | Return the source code of a routine. On Oracle this concatenates lines from `ALL_SOURCE`. Params: `schema`, `name` |
| `listSequences` | List sequences (schema is optional — omit to list across all schemas). Params: `schema` |
| `searchObjects` | Case-insensitive substring search across non-system objects (tables, views, routines, sequences, synonyms). Useful when the LLM has a partial name. Params: `namePattern` |

### Data exploration tools

| Tool | Description |
|---|---|
| `sampleRows` | Return a few rows from a table or view. Shortcut for `SELECT * FROM t LIMIT N`. Params: `schema`, `table`, `limit` (default 10, max 100) |

### Selectivity and distribution tools (predicate / join tuning)

`columnStats` only reports extremes. The other tools answer "how selective is this predicate?"
and "how skewed are the values?" — the information an LLM needs to pick an index column order,
add a partial index, or rewrite a join.

| Tool | Description |
|---|---|
| `columnStats` | Basic column statistics: `total_rows`, `non_null_rows`, `distinct_values`, `min`, `max`. A cheap one-shot scan when only the extremes are needed. Params: `schema`, `table`, `column` |
| `columnDistribution` | Top-N most frequent values of a column plus their share of the table. Surfaces skew (e.g. `70 %` of rows have `status='OK'` — a plain index on `status` is nearly useless). Executes `GROUP BY + COUNT` — prefer a small `topN` on very large tables. Params: `schema`, `table`, `column`, `topN` (default 20, max 1000) |
| `columnHistogram` | Percentile summary for an orderable column: `min`, `max`, `P25`, `P50`, `P75`, `P90`, `P95`, `P99`, plus null counts. Uses SQL:2003 `WITHIN GROUP`: `percentile_cont` for numeric types (interpolated), `percentile_disc` for dates / timestamps / text. Params: `schema`, `table`, `column` |
| `nullRatio` | Null / non-null ratio for **every column** of a table in one scan. Columns returned sorted by descending `null_ratio`; the `sparse` flag is set when more than 50 % of rows are null (candidate for a partial index with `WHERE col IS NOT NULL`). Params: `schema`, `table` |
| `estimateSelectivity` | Planner's row estimate for `SELECT 1 FROM t WHERE <predicate>` — **without running the query**. Uses `EXPLAIN (FORMAT JSON)` (PostgreSQL), `EXPLAIN PLAN` (Oracle), or `SHOWPLAN_XML` (SQL Server). Returns `estimated_rows`, a `baseline_rows` count (no predicate) and the `selectivity` ratio. Reject `;` in the predicate. Params: `schema`, `table`, `predicate` (raw SQL, no `WHERE` keyword) |
| `joinCardinality` | Planner's row estimate for an equi-join between two tables, without executing it. Returns `estimated_rows`, per-side row estimates and `selectivity_vs_cartesian`. Join types: `INNER` (default), `LEFT`, `RIGHT`, `FULL`. Parameter order encodes JOIN direction (matters for `LEFT` / `RIGHT`). Params: `fromSchema`, `fromTable`, `leftColumn`, `toSchema`, `toTable`, `rightColumn`, `joinType` |

### Object statistics tools (query optimization)

| Tool | Description |
|---|---|
| `tableStats` | Per-table size + row-count estimate + activity counters. PG: `pg_class` + `pg_stat_user_tables` (total/heap/indexes/toast bytes, live/dead tuples, last vacuum/analyze, seq vs idx scans). Oracle: `ALL_TABLES` (NUM_ROWS, BLOCKS, LAST_ANALYZED) plus best-effort `DBA_SEGMENTS` size. SQL Server: `sys.tables` / `sys.partitions` / allocation units. Params: `schema`, `table` |
| `indexStats` | Per-index stats for a table or the whole schema: size, scan counter when available, column list, unique/primary flags. PG extras: `idx_tup_read`, `idx_tup_fetch`, `pg_get_indexdef`. Oracle extras: `distinct_keys`, `clustering_factor`, `blevel`, `leaf_blocks`, `last_analyzed`. SQL Server uses `sys.indexes` and allocation units; usage counters are omitted for low-privilege safety. Params: `schema`, `table` (optional) |
| `unusedIndexes` | Indexes with zero recorded scans — candidates for removal. Skips PK / UNIQUE-backing indexes. Params: `schema`, `minSizeBytes` (optional). PostgreSQL only; on Oracle and SQL Server returns a note about engine-specific usage-counter permissions/views |
| `redundantIndexes` | Indexes whose leading columns are a strict prefix of another index on the same table — the shorter one is redundant. Skips unique indexes; requires matching index type. Params: `schema`, `table` (optional) |
| `fkIndexCoverage` | Foreign keys on the child side that lack a supporting index. A classic cause of slow DELETE / UPDATE cascades. Returns a `suggested_index_columns` list ready for `CREATE INDEX`. Params: `schema`, `table` (optional — omit to scan the whole schema) |

### Schema context tools (high-level, for SQL authoring)

These compose lower-level metadata into ready-to-consume packets. Prefer them over manually
chaining `listTables` → `describeTable` → `sampleRows`. Traversal sizes are capped by default to
keep the response compact; raise the caps when needed.

| Tool | Description |
|---|---|
| `tableContext` | Neighbourhood around one table: the table, FK parents, optionally child tables and edges. Params: `schema`, `table`, `depth` (default 1, cap 4), `includeIncoming`, `includeStats`, `includeObserved` |
| `findJoinPaths` | FK-based join paths between two tables (graph traversed in both FK directions; each edge has `joinCondition`). Params: `fromSchema`/`fromTable`, `toSchema`/`toTable`, `maxDepth` (default/cap 4), `maxPaths` (default 5, cap 25), `scanLimit` (default/cap 300), `includeObserved` |
| `schemaBrief` | Plain-text full-schema map: all matching tables/views with column counts, PK, incoming/outgoing relationship counts, key-like columns, central/isolated tables, and capped key FK relationships. Use this first when relevant tables are unknown; follow with `queryContext` for detailed context. Params: `schema`, `terms` (optional substring narrowing), `maxTables` (safety cap; default 2000, cap 5000) |
| `schemaGraph` | Relationship-graph metrics: nodes with in/out degree, central tables, isolated tables, components, cycle hints; optional shortest path. Params: `schema`, `maxTables` (default 50, cap 300), `fromTable`, `toTable`, `maxDepth` |
| `schemaLint` | Lint audit: missing PK, FK without index, FK type mismatch, nullable unique, status/type without CHECK, orphan `*_id`, missing remarks, isolated and wide tables. Params: `schema`, `table`, `checks` (allow-list), `maxTables` (default 50, cap 300), `maxFindings` |
| `queryContext` | Author-grade context from natural-language `terms` and/or explicit `tables`: relevant tables/columns, constraints, allowed values, relationships, join paths between selected tables, optional tiny samples (`includeSamples`). Params: `schema`, `terms`, `tables`, `includeSamples`, `maxTables` (default 12, cap 50 — narrower than the other context tools to keep responses concise) |
| `schemaGraphDot` | DOT/Graphviz ERD: nodes are tables with all columns and types (PK marked 🔑, FK with →). Params: `schema`, `tables` (optional filter) |

### Catalog administration tools

| Tool | Description |
|---|---|
| `rebuildCatalog` | Rebuild the persistent structure snapshot and usage index for comma-separated `schemas` (or the configured/default scope), checkpoint SQLite WAL, and return the `<catalog>.db` path plus the connection it was built for. Writes only to the local catalog |

### Connection tools

| Tool | Description |
|---|---|
| `listConnections` | The databases this server serves: `name` (what to pass as `connection`), `description`, engine kind, default schema, whether a local catalog exists, and whether its pool has been built. Opens no database connection |

### Structure snapshot

Structural metadata (columns, keys, indexes, FKs, views, routines, triggers, sequences) is persisted
in the local SQLite `<catalog>.db` file (the same database as the usage catalog). It is authoritative —
"cache forever", no TTL or staleness detection. It fills lazily (`describeTable` persists each table)
and can be front-loaded per schema with the `rebuildCatalog` tool (scope: the connection's
`structureSnapshotSchemas`, or the default schema). Once a schema is covered, `listTables`, view/routine/trigger/sequence lookups,
`searchObjects` and the usage re-resolver are served from the snapshot; uncovered schemas fall through
to the live database. Live statistics are never cached. The `rebuildCatalog` tool builds the
structure snapshot **and** the usage index into one distributable `<catalog>.db` for a known database.
It checkpoints the SQLite WAL before returning the file path.

Clear it while all server processes are stopped by deleting `<catalog>.db` and adjacent
`<catalog>.db-wal` / `<catalog>.db-shm` files. Legacy H2 `<catalog>.mv.db` files are left untouched
and are not migrated; run `rebuildCatalog` to populate a newly created SQLite catalog.

### Usage catalog tools

The usage catalog indexes known application SQL queries (from JSON/ZIP files) and the database's
own views, routines, and triggers into the persistent SQLite catalog. It enables the agent to discover how tables and
columns are used in practice — what queries reference them, what join patterns exist, and what
business context they belong to.

Configured per connection in `connections.json`:
- `usageCatalogEnabled` (default `true`) — master switch
- `usageCatalogPaths` — paths to JSON/ZIP files with `QueryUsage` records, additional to the
  connection's own `<data-dir>/<name>/usage-catalog` directory.
- The persistent index is built synchronously on first usage-catalog access, never during startup.
- Database-native usage is included in the same index build for views, routines, and triggers.

When the catalog is off, `usageCatalogStatus` reports `catalogEnabled: false`; other public usage
tools return an `argument` error explaining how to enable it.

| Tool | Description |
|---|---|
| `usageCatalogStatus` | Enabled flag, current state (`not_started`, `indexing`, `ready`, `failed`, or `invalidated`), and configured sources |
| `invalidateUsageCatalogCache` | Drop the runtime usage index. The next lookup rebuilds it synchronously from configured files and database-native objects |
| `getQuery` | Full stored record selected by `sourceKind`, `sourcePath`, and optional `sourceUnit`: SQL, parameters, parsed tables/columns/joins, outputs, field usages |
| `listQueries` | List stored queries with filters: `sourcePath` (LIKE), `sourceKind`, `businessDomain`, `tag`, `parseStatus`, `searchText` (case-insensitive full-text across raw SQL, labels, source paths, and domains), `limit`, `offset` |
| `findQueriesByTable` | Find queries referencing a table (case-insensitive, alias-expanded). Params: `schema`, `table` |
| `findQueriesByColumn` | Find queries referencing a column, with usage context (`select`, `where`, `join`, `order_by`, `having`). Params: `schema`, `table`, `column` |
| `observedRelationships` | Aggregate equi-join pairs across all queries: `(left_table.left_col = right_table.right_col)` with support count and contributing query uids. Params: `schema`, `table`, `minSupport` |
| `listKnownTags` | Business tags with their query counts — reuse existing vocabulary. No params |
| `listKnownDomains` | Business domains with their query counts. No params |
| `listKnownKinds` | Source-kinds with their query counts — discover valid values for `listQueries` `sourceKind` filter |

These tools feed the `evidence` bundle (`observedQuery` / `semanticUsage` layers) in
`tableContext` and `findJoinPaths` when `includeObserved=true`.

All tools are **read-only**. Any attempt to run a non-SELECT statement is rejected by the
client-side guard before it reaches the database. In addition, the JDBC connection is marked
read-only, and PostgreSQL uses `default_transaction_read_only=on`. On Oracle and SQL Server,
JDBC read-only mode is best-effort; use a dedicated read-only database user for the strongest guarantee.

## Error responses

All tools share one error shape — a JSON object with at minimum `error` and `kind`:

```json
{"error": "Only SELECT / WITH / EXPLAIN statements are allowed", "kind": "rejected"}
```

| `kind` | When |
|---|---|
| `sql` | The database returned a `SQLException` (syntax, missing object, permission, ...). |
| `argument` | Tool argument was missing or malformed (raised by the tool/service). |
| `rejected` | The read-only guard blocked the SQL before sending it to the database. |
| `not_found` | A `getViewDefinition` / `getRoutineDefinition` / `getTriggerDefinition` lookup matched nothing. The body adds `missing` and `name`. |
| `driver` / `unexpected` / `plan_parse` | Internal driver / unhandled / plan-parser failure. |

`validateQuery` returns its own JSON shape (no `kind` — instead `valid` is the discriminator):

```json
{"valid": true,  "parameters": 1, "columns": 3}
{"valid": false, "stage": "guard|params|driver", "error": "..."}
```

## Choosing a connection

One server process may serve several databases, named in
`<data-dir>/connections.json` (override with `JDBC_MCP_CONNECTIONS_FILE`). Every tool takes
`connection` as its first, required argument, naming the database to run against.

**In an unfamiliar installation, `listConnections` is the first call to make.** It is cheap — it
opens no database connection — and it tells you what exists, what each database is for
(`description`, written by the operator), and which already have a local catalog
(`snapshotAvailable`, a hint that `describeTable`, `schemaBrief` and friends will answer from the
snapshot).

Rules:

- There is no default connection. Every call names its database, including installations that serve
  exactly one — nothing is inferred, so a multi-database session cannot silently read the wrong
  database.
- A missing or unknown name returns an `argument` error listing the available names. Do not guess
  names; call `listConnections`.
- Do not mix databases in one query: there are no cross-connection queries or joins. Read from each
  database separately and combine the results yourself.
- Each connection has its own local catalog, usage index and structure snapshot. `rebuildCatalog`,
  the usage tools and the stats tools all act on the connection you name, and `listConnections`,
  `usageCatalogStatus` and `rebuildCatalog` echo the connection name back so a response cannot be
  mistaken for another database's.
- A database being down affects only calls made against it. If one connection errors, the others
  keep working; `configError` in `listConnections` reports a connection that is misconfigured rather
  than unreachable.

## How an agent should use these tools

Recommended flow when the user asks a data question:

0. If you have not yet established which database the question is about, call `listConnections`
   and pick one by `description`; pass its `name` as `connection` on every call below.
1. If the agent does not already know the schema, prefer the high-level context tools over
   manual chaining. `queryContext` (natural-language terms or explicit table list) returns the
   tables, columns, constraints, allowed values, relationships and join paths in one call.
   Fall back to `schemaBrief` for broader exploration, to `tableContext` for known-table
   neighborhoods, and to `listSchemas` + `listTables` only when those are not enough.
2. If a single table is the focus, call `describeTable` — one call returns columns, PK, FKs,
   indexes, constraints, allowed values from CHECKs, and triggers.
3. Optionally call `sampleRows` to peek at actual data shape.
4. If the usage catalog is enabled, call `findQueriesByTable` or `findQueriesByColumn` to see
   how other queries reference the same tables — learn existing join patterns, filters, and business context.
5. Write the SQL and call `inspectQuery`, `queryLint`, or `resolveQueryLineage` when you want an AST/metadata/lineage sanity pass before touching the database.
6. Call `validateQuery` with the same `params` / `namedParams` you intend to use for execution — fix errors without wasting executions.
7. Call `analyzePlan` (compact LLM-friendly summary) or `explainQuery` (full textual plan) if the query might be expensive.
8. Call `executeQuery`. Use `limit` to keep the response small.
9. If the response has `"truncated": true`, either narrow the query or raise `limit`.

Recommended flow when the user asks to optimize / audit queries or schema:

1. Call `tableStats` for each large table involved — gives the row-count magnitude,
   on-disk size and dead-tuple ratio. Without this the agent cannot tell a 1 K-row
   table from a 100 M-row one.
2. Call `indexStats` on the same tables — size, scan count, uniqueness, columns.
3. Call `fkIndexCoverage` (schema-wide or per-table) to find FK columns without a
   supporting index — each entry already includes `suggested_index_columns`.
4. Call `redundantIndexes` — safe drops that shrink storage and write overhead.
5. Call `unusedIndexes` (PostgreSQL) — indexes that have had zero scans since the
   last stats reset; treat as a strong hint only if the workload has run long enough.
6. Call `schemaLint` for a broader audit (missing PK, nullable unique, FK type mismatch,
   orphan `*_id` columns, isolated and wide tables, etc.).
7. For a specific slow query, combine `analyzePlan` (compact summary of expensive nodes,
   full scans, estimation errors, nested-loop risks, sort spills) + `indexStats` on the
   relevant tables and propose concrete `CREATE INDEX` / rewrite actions. Fall back to
   `explainQuery` only when you need the full textual plan.
8. Before proposing a composite index, call `estimateSelectivity` for each candidate
   predicate on the involved table — place the most selective column first. Use
   `columnDistribution` / `nullRatio` / `columnHistogram` on the same columns to detect
   skew or high null ratio (strong signals for a partial index). For join-heavy queries,
   `joinCardinality` predicts the output size without executing the join.
9. To compare rewrites, use `benchmarkQuery` (cold + warm wall-clock) or `timedQuery`
   (`pg_stat_statements` deltas on PostgreSQL).

Notes on the structure snapshot:

- Structural metadata is persisted in the local `<catalog>.db` file ("cache forever", no TTL).
  Repeated `tableContext`, `findJoinPaths`, `schemaLint`, `schemaGraph`, `queryContext`,
  `describeTable` and `searchObjects` calls are served from it for covered schemas.
- Live counters (table/index/column stats, samples, plans) are **never** cached.
- Front-load a schema and prebuild a distributable `<catalog>.db` (structure + usage) with
  `rebuildCatalog`; it checkpoints WAL before returning. The snapshot survives restarts.


## Troubleshooting

- **"Gradle requires JVM 17 or later" / toolchain error** — set `JAVA_HOME` to a JDK 21+ before
  running `./gradlew`.
- **Connection refused / authentication failed** — verify the entry's `url`/`username`/`password`
  in `connections.json` with the native CLI (`psql` for PostgreSQL, `sqlplus user/password@...` for
  Oracle, `sqlcmd -S host,1433 -d database -U user -P password` for SQL Server).
- **`{"kind":"rejected","error":"Only SELECT / WITH / EXPLAIN statements are allowed"}`** — guard
  triggered. This is expected for any write statement. For edge cases where a read-only
  operation is wrapped in something the guard does not recognize, set `"readonlyGuard": "off"` on
  that connection.
  Connection-level read-only flags stay on; Oracle and SQL Server treat them as best-effort.
- **Oracle: empty `describeTable` / `listTables`** — Oracle stores unquoted identifiers in upper
  case. Pass `CUSTOMERS` rather than `customers`.
- **Oracle write attempt reached the database** — this should normally be blocked by the guard first.
  If `readonlyGuard` is `off`, rely on a read-only Oracle user; JDBC `setReadOnly(true)` is only a
  best-effort hint for Oracle.
