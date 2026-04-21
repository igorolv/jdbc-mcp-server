# JDBC MCP Server — Setup Guide for AI Agents

This is a local MCP server that provides read-only access to PostgreSQL and Oracle databases.
It exposes 24 read-only tools for executing SELECT queries, obtaining execution plans,
exploring database schema, tables, columns, indexes, foreign keys, views, routines and sequences,
gathering object-level statistics that help with query optimisation, and analysing column-level
distribution, null ratios, predicate selectivity and join cardinality estimates.

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

The server exposes **24 read-only MCP tools**.

### Query tools

| Tool | Description |
|---|---|
| `executeQuery` | Run a SELECT / WITH / EXPLAIN statement. Params: `sql`, `params` (array of values for `?` placeholders) or `namedParams` (object for `:name` placeholders), `limit`, `timeoutSeconds`, `format` (`json` default, `markdown`, `csv`). Result includes `truncated` flag if the row limit was hit |
| `explainQuery` | Return the execution plan. PostgreSQL: `EXPLAIN (FORMAT TEXT)`. Oracle: `EXPLAIN PLAN FOR` + `DBMS_XPLAN.DISPLAY`. `analyze=true` enables `EXPLAIN ANALYZE` on PostgreSQL (actually runs the query). Params: `sql`, `params` (`?`) or `namedParams` (`:name`), `analyze` |
| `analyzePlan` | Compact, LLM-friendly summary of the execution plan: top-cost nodes, full scans on large relations, estimation errors (planner vs. reality — requires `analyze=true` on PG), risky nested loops with large outer input, disk sort spills. PostgreSQL: `EXPLAIN (FORMAT JSON)` / `EXPLAIN ANALYZE`. Oracle: `EXPLAIN PLAN` + `PLAN_TABLE` (static only, `analyze` ignored). Params: `sql`, `params` (`?`) or `namedParams` (`:name`), `analyze` |
| `validateQuery` | Validate a statement without running it — read-only guard + driver-side `prepareStatement`. Params: `sql`, `params` (`?`) or `namedParams` (`:name`) |

### Metadata tools

| Tool | Description |
|---|---|
| `listSchemas` | List all schemas. System schemas (`pg_catalog`, `information_schema`, `SYS`, ...) are hidden unless `includeSystem=true` |
| `listTables` | List tables and views in a schema. Params: `schema`, `namePattern` (JDBC wildcards `%` / `_`), `types` (comma-separated, e.g. `TABLE,VIEW,MATERIALIZED VIEW`) |
| `describeTable` | Full table/view description in a single call: columns (name, type, size, nullable, default, remarks), primary key, unique constraints, indexes, outgoing foreign keys, tables referencing this one. Params: `schema`, `table` |
| `getViewDefinition` | Return the SQL definition of a view / materialized view. Params: `schema`, `name` |
| `listRoutines` | List functions, procedures, packages. Params: `schema`, `namePattern` |
| `getRoutineDefinition` | Return the source code of a routine. On Oracle this concatenates lines from `ALL_SOURCE`. Params: `schema`, `name` |
| `listSequences` | List sequences (schema is optional — omit to list across all schemas). Params: `schema` |
| `searchObjects` | Case-insensitive substring search across non-system objects (tables, views, routines, sequences, synonyms). Useful when the LLM has a partial name. Params: `pattern` |

### Data exploration tools

| Tool | Description |
|---|---|
| `sampleRows` | Return a few rows from a table or view. Shortcut for `SELECT * FROM t LIMIT N`. Params: `schema`, `table`, `limit` (default 10, max 100) |
| `columnStats` | Basic column statistics: `total_rows`, `non_null_rows`, `distinct_values`, `min`, `max`. Params: `schema`, `table`, `column` |

### Selectivity and distribution tools (predicate / join tuning)

`columnStats` only reports extremes. These tools answer "how selective is this predicate?" and
"how skewed are the values?" — the information an LLM needs to pick an index column order, add
a partial index, or rewrite a join.

| Tool | Description |
|---|---|
| `columnDistribution` | Top-N most frequent values of a column plus their share of the table. Surfaces skew (e.g. `70 %` of rows have `status='OK'` — a plain index on `status` is nearly useless). Executes `GROUP BY + COUNT` — prefer a small `topN` on very large tables. Params: `schema`, `table`, `column`, `topN` (default 20, max 1000) |
| `columnHistogram` | Percentile summary for an orderable column: `min`, `max`, `P25`, `P50`, `P75`, `P90`, `P95`, `P99`, plus null counts. Uses SQL:2003 `WITHIN GROUP`: `percentile_cont` for numeric types (interpolated), `percentile_disc` for dates / timestamps / text. Params: `schema`, `table`, `column` |
| `nullRatio` | Null / non-null ratio for **every column** of a table in one scan. Columns returned sorted by descending `null_ratio`; the `sparse` flag is set when more than 50 % of rows are null (candidate for a partial index with `WHERE col IS NOT NULL`). Params: `schema`, `table` |
| `estimateSelectivity` | Planner's row estimate for `SELECT 1 FROM t WHERE <predicate>` — **without running the query**. Uses `EXPLAIN (FORMAT JSON)` (PostgreSQL) or `EXPLAIN PLAN` (Oracle). Returns `estimated_rows`, a `baseline_rows` count (no predicate) and the `selectivity` ratio. Reject `;` in the predicate. Params: `schema`, `table`, `predicate` (raw SQL, no `WHERE` keyword) |
| `joinCardinality` | Planner's row estimate for an equi-join between two tables, without executing it. Returns `estimated_rows`, per-side row estimates and `selectivity_vs_cartesian`. Join types: `INNER` (default), `LEFT`, `RIGHT`, `FULL`. Params: `leftSchema`, `leftTable`, `leftColumn`, `rightSchema`, `rightTable`, `rightColumn`, `joinType` |

### Object statistics tools (query optimisation)

| Tool | Description |
|---|---|
| `tableStats` | Per-table size + row-count estimate + activity counters. PG: `pg_class` + `pg_stat_user_tables` (total/heap/indexes/toast bytes, live/dead tuples, last vacuum/analyze, seq vs idx scans). Oracle: `ALL_TABLES` (NUM_ROWS, BLOCKS, LAST_ANALYZED) plus best-effort `DBA_SEGMENTS` size. Params: `schema`, `table` |
| `indexStats` | Per-index stats for a table or the whole schema: size, scan counters, column list, unique/primary flags. PG extras: `idx_tup_read`, `idx_tup_fetch`, `pg_get_indexdef`. Oracle extras: `distinct_keys`, `clustering_factor`, `blevel`, `leaf_blocks`, `last_analyzed`. Params: `schema`, `table` (optional) |
| `unusedIndexes` | Indexes with zero recorded scans — candidates for removal. Skips PK / UNIQUE-backing indexes. Params: `schema`, `minSizeBytes` (optional). PostgreSQL only; on Oracle returns a note pointing to `DBA_INDEX_USAGE` / `V$OBJECT_USAGE` |
| `redundantIndexes` | Indexes whose leading columns are a strict prefix of another index on the same table — the shorter one is redundant. Skips unique indexes; requires matching index type. Params: `schema`, `table` (optional) |
| `fkIndexCoverage` | Foreign keys on the child side that lack a supporting index. A classic cause of slow DELETE / UPDATE cascades. Returns a `suggested_index_columns` list ready for `CREATE INDEX`. Params: `schema`, `table` (optional — omit to scan the whole schema) |

All tools are **read-only**. Any attempt to run a non-SELECT statement is rejected by the
client-side guard before it reaches the database. In addition, the JDBC connection is marked
read-only, PostgreSQL uses `default_transaction_read_only=on`, and Oracle uses
`SET TRANSACTION READ ONLY`.

## How an agent should use these tools

Recommended flow when the user asks a data question:

1. If the agent does not already know the schema, call `listSchemas` and `listTables`.
2. Call `describeTable` on each table involved — one call returns everything needed
   (columns, PK, FKs, indexes).
3. Optionally call `sampleRows` to peek at actual data shape.
4. Write the SQL and call `validateQuery` with the same `params` / `namedParams` you intend to use for execution — fix errors without wasting executions.
5. Call `explainQuery` if the query might be expensive.
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
6. For a specific slow query, combine `analyzePlan` (compact summary of expensive nodes,
   full scans, estimation errors, nested-loop risks, sort spills) + `indexStats` on the
   relevant tables and propose concrete `CREATE INDEX` / rewrite actions. Fall back to
   `explainQuery` only when you need the full textual plan.
7. Before proposing a composite index, call `estimateSelectivity` for each candidate
   predicate on the involved table — place the most selective column first. Use
   `columnDistribution` / `nullRatio` on the same columns to detect skew or high null
   ratio (strong signals for a partial index). For join-heavy queries, `joinCardinality`
   predicts the output size without executing the join.

## Troubleshooting

- **"Gradle requires JVM 17 or later" / toolchain error** — set `JAVA_HOME` to a JDK 25+ before
  running `./gradlew`.
- **Connection refused / authentication failed** — verify URL/user/password with the native CLI
  (`psql "$JDBC_URL"` for PostgreSQL, `sqlplus $JDBC_USERNAME/$JDBC_PASSWORD@...` for Oracle).
- **`Rejected: Only SELECT / WITH / EXPLAIN statements are allowed`** — guard triggered. This
  is expected for any write statement. For edge cases where a read-only operation is wrapped in
  something the guard does not recognise, set `JDBC_READONLY_GUARD=off`. The connection-level
  read-only enforcement stays on.
- **Oracle: empty `describeTable` / `listTables`** — Oracle stores unquoted identifiers in upper
  case. Pass `CUSTOMERS` rather than `customers`.
- **Oracle: `ORA-01456: may not perform insert/delete/update operation inside a READ ONLY transaction`** —
  that is the server refusing a write. Guard already blocked it upstream; this is a defence in depth.
