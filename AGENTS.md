# JDBC MCP Server — Setup Guide for AI Agents

This is a local MCP server that provides read-only access to PostgreSQL and Oracle databases.
It exposes 38 read-only tools across eight groups:

- **Query** — execute SELECT/WITH/EXPLAIN, validate without running, get plain or LLM-summarised plans.
- **Benchmark** — wall-clock cost of a query, optionally with `pg_stat_statements` deltas.
- **Metadata** — schemas, tables, columns, indexes, FKs, constraints, triggers, views, routines, sequences, object search.
- **Data exploration** — sample rows, basic column stats.
- **Selectivity / distribution** — top-N, percentiles, null ratios, planner-only predicate and join estimates.
- **Object statistics** — table/index size and activity, unused/redundant indexes, FK index coverage.
- **Schema context** — high-level snapshots, table neighbourhoods, FK join paths, schema lint, ERD/DOT export.
- **Snapshot / cache** — in-memory metadata snapshot with TTL plus refresh / inspect / invalidate tools.

The server communicates over stdio (stdin/stdout). Both PostgreSQL and Oracle JDBC drivers
are bundled inside the fat jar.

## Prerequisites

- JDK 25+ installed (check with `java -version`)
- A database account. A **read-only** database user is strongly recommended — see the README
  for SQL snippets to create one in PostgreSQL or Oracle.

## Step 1: Get database credentials from the user

Before building, ask the user for:

1. **JDBC URL** — e.g.
   - PostgreSQL: `jdbc:postgresql://<host>:5432/<database>`
   - Oracle: `jdbc:oracle:thin:@//<host>:1521/<service>`
2. **Username** (preferably a read-only user)
3. **Password**

Optionally:

- **Default schema** for metadata tools (`JDBC_DEFAULT_SCHEMA`). If omitted, the server uses the
  connection's current schema. On Oracle, this defaults to the connecting user's schema (UPPER CASE).
- **Per-statement timeout** — `JDBC_QUERY_TIMEOUT_SECONDS` (default 30, `0` disables).
- **Row cap** — `JDBC_MAX_ROWS` (default 1000); responses include `truncated: true` when hit.
- **Read-only guard** — `JDBC_READONLY_GUARD` (`strict` default, `off` disables the client-side check; connection-level read-only flags stay on).
- **Metadata snapshot cache** — `JDBC_METADATA_CACHE_TTL_SECONDS` (default 300, `0` disables) and
  `JDBC_METADATA_CACHE_MAX_ENTRIES` (default 2000). Caches structural metadata only; live stats are not cached.

## Step 2: Build

```bash
# If the default JDK is < 25, set JAVA_HOME explicitly, e.g.:
# export JAVA_HOME="$HOME/.jdks/jdk-25.0.2"

cd <path-to-this-project>
./gradlew build
```

The resulting jar: `build/libs/jdbc-mcp-server.jar` (~32 MB, both JDBC drivers bundled).

## Step 3: Verify the server starts

```bash
JDBC_URL=<url>       \
JDBC_USERNAME=<user> \
JDBC_PASSWORD=<pw>   \
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
  "env": {
    "JDBC_URL": "<url>",
    "JDBC_USERNAME": "<user>",
    "JDBC_PASSWORD": "<pw>"
  }
}
```

**Where to put it:**

| Client | Config file | Server key |
|---|---|---|
| Claude Code | `~/.claude/settings.json` → `"mcpServers"` | `"jdbc"` |
| Qwen Code | `~/.qwen/settings.json` → `"mcpServers"` | `"jdbc"` |
| VS Code (Copilot/Continue) | `.vscode/mcp.json` → `"servers"` | `"jdbc"` |
| Cursor | `.cursor/mcp.json` → `"mcpServers"` | `"jdbc"` |
| Claude Desktop | `claude_desktop_config.json` → `"mcpServers"` | `"jdbc"` |

**Example for Claude Code (`~/.claude/settings.json`):**
```json
{
  "mcpServers": {
    "jdbc": {
      "command": "java",
      "args": ["-jar", "<absolute-path-to>/jdbc-mcp-server.jar"],
      "env": {
        "JDBC_URL": "jdbc:postgresql://db.example.com:5432/myapp",
        "JDBC_USERNAME": "ai_readonly",
        "JDBC_PASSWORD": "<password>"
      }
    }
  }
}
```

After updating the config, restart the client so it picks up the new MCP server.

## Available tools

The server exposes **38 read-only MCP tools**.

### Query tools

| Tool | Description |
|---|---|
| `executeQuery` | Run a SELECT / WITH / EXPLAIN statement. Params: `sql`, `params` (array of values for `?` placeholders) or `namedParams` (object for `:name` placeholders), `limit`, `timeoutSeconds`, `format` (`json` default, `markdown`, `csv`). Result includes `truncated` flag if the row limit was hit |
| `explainQuery` | Return the execution plan. PostgreSQL: `EXPLAIN (FORMAT TEXT)`. Oracle: `EXPLAIN PLAN FOR` + `DBMS_XPLAN.DISPLAY`. `analyze=true` enables `EXPLAIN ANALYZE` on PostgreSQL (actually runs the query). Params: `sql`, `params` (`?`) or `namedParams` (`:name`), `analyze` |
| `analyzePlan` | Compact, LLM-friendly summary of the execution plan: top-cost nodes, full scans on large relations, estimation errors (planner vs. reality — requires `analyze=true` on PG), risky nested loops with large outer input, disk sort spills. PostgreSQL: `EXPLAIN (FORMAT JSON)` / `EXPLAIN ANALYZE`. Oracle: `EXPLAIN PLAN` + `PLAN_TABLE` (static only, `analyze` ignored). Params: `sql`, `params` (`?`) or `namedParams` (`:name`), `analyze` |
| `validateQuery` | Validate a statement without running it — read-only guard + driver-side `prepareStatement`. Params: `sql`, `params` (`?`) or `namedParams` (`:name`) |

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
| `estimateSelectivity` | Planner's row estimate for `SELECT 1 FROM t WHERE <predicate>` — **without running the query**. Uses `EXPLAIN (FORMAT JSON)` (PostgreSQL) or `EXPLAIN PLAN` (Oracle). Returns `estimated_rows`, a `baseline_rows` count (no predicate) and the `selectivity` ratio. Reject `;` in the predicate. Params: `schema`, `table`, `predicate` (raw SQL, no `WHERE` keyword) |
| `joinCardinality` | Planner's row estimate for an equi-join between two tables, without executing it. Returns `estimated_rows`, per-side row estimates and `selectivity_vs_cartesian`. Join types: `INNER` (default), `LEFT`, `RIGHT`, `FULL`. Parameter order encodes JOIN direction (matters for `LEFT` / `RIGHT`). Params: `fromSchema`, `fromTable`, `leftColumn`, `toSchema`, `toTable`, `rightColumn`, `joinType` |

### Object statistics tools (query optimisation)

| Tool | Description |
|---|---|
| `tableStats` | Per-table size + row-count estimate + activity counters. PG: `pg_class` + `pg_stat_user_tables` (total/heap/indexes/toast bytes, live/dead tuples, last vacuum/analyze, seq vs idx scans). Oracle: `ALL_TABLES` (NUM_ROWS, BLOCKS, LAST_ANALYZED) plus best-effort `DBA_SEGMENTS` size. Params: `schema`, `table` |
| `indexStats` | Per-index stats for a table or the whole schema: size, scan counters, column list, unique/primary flags. PG extras: `idx_tup_read`, `idx_tup_fetch`, `pg_get_indexdef`. Oracle extras: `distinct_keys`, `clustering_factor`, `blevel`, `leaf_blocks`, `last_analyzed`. Params: `schema`, `table` (optional) |
| `unusedIndexes` | Indexes with zero recorded scans — candidates for removal. Skips PK / UNIQUE-backing indexes. Params: `schema`, `minSizeBytes` (optional). PostgreSQL only; on Oracle returns a note pointing to `DBA_INDEX_USAGE` / `V$OBJECT_USAGE` |
| `redundantIndexes` | Indexes whose leading columns are a strict prefix of another index on the same table — the shorter one is redundant. Skips unique indexes; requires matching index type. Params: `schema`, `table` (optional) |
| `fkIndexCoverage` | Foreign keys on the child side that lack a supporting index. A classic cause of slow DELETE / UPDATE cascades. Returns a `suggested_index_columns` list ready for `CREATE INDEX`. Params: `schema`, `table` (optional — omit to scan the whole schema) |

### Schema context tools (high-level, for SQL authoring)

These compose lower-level metadata into ready-to-consume packets. Prefer them over manually
chaining `listTables` → `describeTable` → `sampleRows`. Traversal sizes are capped by default to
keep the response compact; raise the caps when needed.

| Tool | Description |
|---|---|
| `schemaOverview` | Compact schema snapshot: tables/views, columns, PK/FK, indexes, relationship edges. Params: `schema`, `namePattern`, `includeViews`, `includeStats`, `includeInferred` (`*_id` heuristic), `maxTables` (default 50, cap 300) |
| `tableContext` | Neighbourhood around one table: the table, FK parents, optionally child tables and edges. Params: `schema`, `table`, `depth` (default 1, cap 4), `includeIncoming`, `includeStats`, `includeInferred`, `inferredScanLimit` (default 300, cap 300 — only used when `includeInferred=true`, controls how many schema tables are scanned for `*_id` matches) |
| `findJoinPaths` | FK-based join paths between two tables (graph traversed in both FK directions; each edge has `joinCondition`). Params: `fromSchema`/`fromTable`, `toSchema`/`toTable`, `maxDepth` (default 4), `maxPaths` (default 5), `scanLimit` (default 300, cap 300), `includeInferred` |
| `schemaBrief` | Plain-text synopsis: hub tables, fact/detail, lookup/reference, key relationships, enum-like CHECK columns, suspicious implicit joins. Use when full JSON would be too verbose. Params: `schema`, `terms` (optional substring narrowing), `maxTables` (default 50, cap 300), `includeInferred` |
| `schemaGraph` | Relationship-graph metrics: nodes with in/out degree, central tables, isolated tables, components, cycle hints; optional shortest path. Params: `schema`, `maxTables` (default 50, cap 300), `includeInferred`, `fromTable`, `toTable`, `maxDepth` |
| `schemaLint` | Lint audit: missing PK, FK without index, FK type mismatch, inferred-but-undeclared relationships, nullable unique, status/type without CHECK, orphan `*_id`, missing remarks, isolated and wide tables. Params: `schema`, `table`, `checks` (allow-list), `maxTables` (default 50, cap 300), `maxFindings`, `includeInferred` |
| `queryContext` | Author-grade context from natural-language `terms` and/or explicit `tables`: relevant tables/columns, constraints, allowed values, relationships, join paths between selected tables, optional tiny samples (`includeSamples`). Params: `schema`, `terms`, `tables`, `includeSamples`, `maxTables` (default 12, cap 50 — narrower than the other context tools to keep responses concise), `includeInferred` |
| `schemaGraphDot` | DOT/Graphviz ERD: nodes are tables with all columns and types (PK marked 🔑, FK with →); declared FK edges are solid, inferred `*_id` edges are dashed grey. Params: `schema`, `tables` (optional filter), `includeInferred` |

### Snapshot / metadata cache tools

Structural metadata (columns, keys, indexes, FKs, constraints, triggers) is cached in memory with
a TTL set by `JDBC_METADATA_CACHE_TTL_SECONDS` (default 300, `0` disables). Live statistics are
not cached. Hard cap on entries: `JDBC_METADATA_CACHE_MAX_ENTRIES` (default 2000).

| Tool | Description |
|---|---|
| `getSchemaSnapshot` | Meta-info about the cache (TTL, hit/miss counters, cached table names per schema). Does not return full table descriptions — use `schemaOverview` for that. Params: `schema` (optional filter) |
| `refreshSchemaSnapshot` | Invalidate and eagerly re-warm the cache. With `table` — only that table; with `schema` — all tables in the schema; with neither — full clear (no warm). Params: `schema`, `table`, `maxTables` (default 300) |
| `invalidateSnapshot` | Drop cached entries without re-warming. Params: `schema`, `table` |

All tools are **read-only**. Any attempt to run a non-SELECT statement is rejected by the
client-side guard before it reaches the database. In addition, the JDBC connection is marked
read-only, PostgreSQL uses `default_transaction_read_only=on`, and Oracle uses
`SET TRANSACTION READ ONLY`.

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

## How an agent should use these tools

Recommended flow when the user asks a data question:

1. If the agent does not already know the schema, prefer the high-level context tools over
   manual chaining. `queryContext` (natural-language terms or explicit table list) returns the
   tables, columns, constraints, allowed values, relationships and join paths in one call.
   Fall back to `schemaOverview` / `tableContext` / `schemaBrief` for broader exploration, and
   to `listSchemas` + `listTables` only when those are not enough.
2. If a single table is the focus, call `describeTable` — one call returns columns, PK, FKs,
   indexes, constraints, allowed values from CHECKs, and triggers.
3. Optionally call `sampleRows` to peek at actual data shape.
4. Write the SQL and call `validateQuery` with the same `params` / `namedParams` you intend to use for execution — fix errors without wasting executions.
5. Call `analyzePlan` (compact LLM-friendly summary) or `explainQuery` (full textual plan) if the query might be expensive.
6. Call `executeQuery`. Use `limit` to keep the response small.
7. If the response has `"truncated": true`, either narrow the query or raise `limit`.

Recommended flow when the user asks to optimise / audit queries or schema:

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
   inferred-but-undeclared relationships, isolated and wide tables, etc.).
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

Notes on the metadata snapshot cache:

- Structural metadata is cached in memory with a TTL (default 300 s; set
  `JDBC_METADATA_CACHE_TTL_SECONDS=0` to disable). Repeated `schemaOverview`,
  `tableContext`, `findJoinPaths`, `schemaLint`, `schemaGraph`, `queryContext` and
  `describeTable` calls are served from the cache.
- Live counters (table/index/column stats, samples, plans) are **never** cached.
- If an external DDL changes the schema during a session, call `refreshSchemaSnapshot`
  (warms the cache) or `invalidateSnapshot` (drops it) to force a re-read. Use
  `getSchemaSnapshot` to inspect what is currently cached and the hit/miss counters.

## Troubleshooting

- **"Gradle requires JVM 17 or later" / toolchain error** — set `JAVA_HOME` to a JDK 25+ before
  running `./gradlew`.
- **Connection refused / authentication failed** — verify URL/user/password with the native CLI
  (`psql "$JDBC_URL"` for PostgreSQL, `sqlplus $JDBC_USERNAME/$JDBC_PASSWORD@...` for Oracle).
- **`{"kind":"rejected","error":"Only SELECT / WITH / EXPLAIN statements are allowed"}`** — guard
  triggered. This is expected for any write statement. For edge cases where a read-only
  operation is wrapped in something the guard does not recognise, set `JDBC_READONLY_GUARD=off`.
  The connection-level read-only enforcement stays on.
- **Oracle: empty `describeTable` / `listTables`** — Oracle stores unquoted identifiers in upper
  case. Pass `CUSTOMERS` rather than `customers`.
- **Oracle: `ORA-01456: may not perform insert/delete/update operation inside a READ ONLY transaction`** —
  that is the server refusing a write. Guard already blocked it upstream; this is a defence in depth.
