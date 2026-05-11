# JDBC MCP Server

[![SafeSkill 50/100](https://img.shields.io/badge/SafeSkill-50%2F100_Use%20with%20Caution-orange)](https://safeskill.dev/scan/igorolv-jdbc-mcp-server)
A local MCP server for read-only access to PostgreSQL, Oracle, and Microsoft SQL Server databases.
It lets AI agents such as Claude Code, Cursor, VS Code Copilot, and others write SQL queries,
inspect execution plans, and explore database structure: tables, columns, indexes, foreign keys,
views, functions, and sequences.

PostgreSQL, Oracle, and Microsoft SQL Server JDBC drivers are bundled into the fat jar, so no
extra driver installation is required.

## Why This Exists

Scenario: you ask an LLM to "check the database and show how many orders we had by status last
month." Without this server, the LLM may:

- invent table and column names;
- miss the real schema details, such as nullable fields, types, and foreign keys;
- accidentally generate a `DELETE` or `TRUNCATE` while "reasoning."

With this server, the LLM can:

1. call `schemaOverview`, `schemaBrief`, or `queryContext` to get ready-to-use schema context: tables, columns, relationships, and constraints;
2. refine the context with `tableContext` around a specific table or `findJoinPaths` for JOIN path discovery;
3. write a query and optionally call `inspectQuery`, `queryLint`, or `resolveQueryLineage` for AST, metadata, and view/routine lineage checks;
4. call `validateQuery` with the same `params` or `namedParams` that will be used for execution, validating syntax without running the query;
5. call `explainQuery` when a plan is needed;
6. call `executeQuery` to fetch data.

Any non-SELECT query is blocked before it reaches the database.

Beyond live schema introspection, the server also keeps a local **usage catalog** of known SQL
queries used by applications and reports against the inspected database, together with their
business context — parameter meanings, output column descriptions, and where each output is
rendered (Excel cell, dashboard widget, BI Publisher region). This lets the LLM answer questions
like *"which production reports already touch this column?"* and *"what business label does this
field have in the customer card?"* against a curated body of evidence instead of guessing from
names alone. The catalog is also the source of the typed three-layer `evidence` bundle on
relationship edges, so undeclared joins observed in production queries are treated as first-class
hints alongside declared FKs. See *Usage Catalog* below.

## Architecture

```text
+-------------+     stdio      +------------------+      JDBC      +----------+
|  AI agent   | <------------> |  jdbc-mcp-       | -------------> | database |
| (Claude Code|   stdin/stdout |  server (Java)   |  read-only     | PG/OCI/  |
|  Cursor...) |                |                  |  connection    | SQLServer|
+-------------+                +------------------+                +----------+
```

The protocol is `stdio` only. The client starts the server as a child process.

## MCP Tools

### Query

| Tool | Description |
|---|---|
| `executeQuery` | Execute a `SELECT`, `WITH`, or `EXPLAIN` statement and return JSON. Parameters: `sql`, `params` (array for `?`) or `namedParams` (object for `:name`), `limit`, `timeoutSeconds`. The result is marked with `truncated: true` if the row limit is hit |
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
| `describeTable` | Full object description in one call: columns (type, size, nullable, default, remarks), primary key, unique constraints, indexes, outgoing FKs, and incoming FKs |
| `getTriggerDefinition` | Trigger body for one named trigger. Parameters: `schema`, `table`, `trigger` |
| `getViewDefinition` | SQL definition of a view |
| `listRoutines` | Functions, procedures, and packages in a schema |
| `getRoutineDefinition` | Function or procedure source code. On Oracle, all `ALL_SOURCE` lines are concatenated in order |
| `listSequences` | Sequences in a schema |
| `searchObjects` | Case-insensitive substring search across all non-system objects: tables, views, functions, sequences |

### Schema Context

High-level tools for quick schema orientation and SQL authoring. Instead of manually calling
`listTables` -> `describeTable` -> `sampleRows` for each table, an LLM can get ready-to-use
context in one call: tables, columns, relationships, constraints, and sample rows.

| Tool | Description |
|---|---|
| `schemaOverview` | Compact schema snapshot for SQL authoring: tables/views, columns, primary keys, foreign keys, indexes, and relationship edges. Parameters: `schema`, `namePattern` (with `%` / `_`), `includeViews`, `includeStats`, `includeObserved` (decorate edges with usage-catalog evidence — see *Edge evidence* below), `maxTables` (default 50, max 300) |
| `tableContext` | Context around one table: the table itself, FK parents, and optionally child tables and relationship edges. FK traversal uses the requested depth (default 1, max 4). Parameters: `schema`, `table`, `depth`, `includeIncoming`, `includeStats`, `includeObserved` |
| `findJoinPaths` | Find JOIN paths between two tables through FKs. The graph is traversed in both directions and each edge includes `joinCondition` and a typed `evidence` bundle (see *Edge evidence* below). Parameters: `fromSchema` / `fromTable`, `toSchema` / `toTable`, `maxDepth` (default 4), `maxPaths` (default 5), `scanLimit` (default 300, max 300), `includeObserved` |
| `schemaBrief` | Compact text summary of a schema: hub tables, fact/detail tables, lookup/reference tables, key relationships, enum-like CHECK columns, and brief table notes. Useful when full JSON would be too verbose. Parameters: `schema`, `terms` (optional substring search), `maxTables` |
| `schemaGraph` | Schema relationship graph metrics: nodes with in/out degree and classification, edges, central tables, isolated tables, connected components, and cycle hints. Optionally includes the shortest path between two tables |
| `schemaLint` | Schema lint audit: missing primary keys, FKs without indexes, FK type mismatches, nullable unique constraints, status/type columns without CHECK constraints, orphan `*_id` columns, missing remarks, isolated tables, and wide tables. Checks are configurable through `checks` |
| `queryContext` | Build compact SQL-authoring context from search terms and/or explicit tables. Finds relevant tables and columns using declared schema names/comments plus usage-catalog semantic evidence when available, includes constraints and allowed values, relationships and JOIN paths between selected tables, and optionally sample rows (up to 3 per table) |
| `schemaGraphDot` | DOT/Graphviz representation of the schema relationship graph. Nodes are tables with all columns and types (`PK` and `FK` marked inline), edges include JOIN conditions. Parameters: `schema`, `tables` (optional comma-separated filter) |

#### Edge evidence

When `includeObserved` is left unset, `schemaOverview` / `tableContext` / `findJoinPaths` enable
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
builds an in-memory H2 index with extracted tables / columns / equi-join pairs as facts. JSON files
remain authoritative for file-backed records; native records are refreshed from live metadata.

**Why this exists.** The metadata tools answer "what tables and columns exist". The usage catalog
answers "how are they actually used by applications". With both, an LLM can replace guesses about
undeclared joins with evidence-based reasoning ("these two columns are joined in 17 production
reports, here are their uids").

**Identity.** Each query is keyed by a textual `uid` derived from
`(dataSource, source.path, source.unit)`:

```
{dataSource}/{source.path}#{source.unit}
```

The `#unit` suffix is omitted when there is no unit. Examples — assuming a demo `SHOP` database
with `customer`, `customer_notes`, and `order` tables:

```
SHOP/reports/customers/CustomerCard.xdo#CUST
SHOP/manual/ad-hoc-2026-05-01
SHOP/com/example/shop/dao/OrderDao.java#findByCustomer
```

`dataSource` and `source.unit` must not contain `/` or `#`; `source.path` must not contain `#`.
Duplicate uids across input files are reported by `usageCatalogStatus`; the first record wins for
that index build.

**Where the files live.** Configure `JDBC_USAGE_CATALOG_PATHS` as a comma-separated list of
directories, `.json` files, or `.zip` archives. Directories are scanned recursively for `*.json`;
zip archives are scanned for JSON entries. Set `JDBC_USAGE_CATALOG_ENABLED=false` to disable the
catalog: lookup tools return empty results with `catalog_enabled: false` so the agent can degrade
gracefully.

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
object is still kept as a provenance record. Use `JDBC_USAGE_NATIVE_SCHEMAS=schema1,schema2` to
scan explicit schemas.

**Runtime index.** The server never builds the usage index on startup. The first usage-catalog
lookup builds the complete runtime index synchronously from both file-backed records and
database-native objects. The index is rebuildable and in-memory; it is not the source of truth.
Use `invalidateUsageCatalogCache` after changing source files or database object definitions; the
next lookup rebuilds the index.

**Local-only writes.** The usage catalog never writes to the inspected JDBC database
(PostgreSQL / Oracle / SQL Server). The existing `ReadOnlyGuard` and connection-level protections
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
| `usageCatalogStatus` | Runtime index status: configured sources, indexing state, counts, duplicate uids, invalid files and load errors |
| `invalidateUsageCatalogCache` | Drop the runtime index. The next lookup rebuilds it synchronously from configured files and database-native objects |
| `getQuery` | Full record by uid: header, parameters, parsed tables/columns/join pairs, outputs (with derived columns), and field usages |
| `listQueries` | Paginated listing with optional filters: `dataSource`, `sourcePath` (LIKE — `%` / `_` allowed), `sourceKind`, `businessDomain`, `tag`, `parseStatus`, `searchText` (case-insensitive full-text search across raw SQL, normalized SQL, labels, source paths, and domains) |
| `findQueriesByTable` | All catalog queries that reference a given table. Case-insensitive matching against alias-resolved, uppercased table names. Optional `schema` filter |
| `findQueriesByColumn` | All catalog queries that reference a given column, with the SQL `context` of the reference (`select` / `where` / `join` / `order_by` / `having`). Optional `schema` and `table` filters |
| `observedRelationships` | Aggregate observed equi-join pairs across stored queries, grouped by `(left_table.left_column = right_table.right_column)` with `support` count and contributing query uids. Non-equi joins (BETWEEN, function-based) are excluded. The same data feeds the `observedQuery` layer of the relationship `evidence` bundle in `schemaOverview` / `tableContext` / `findJoinPaths` |
| `listKnownTags` | Tags currently used in the catalog, with query counts. Lets the agent reuse a stable vocabulary across ingest calls |
| `listKnownDomains` | Same for `businessDomain` values |
| `listKnownKinds` | Source-kinds currently used in the catalog with their query counts. Helps the agent discover valid values for `listQueries` `sourceKind` filter |

**Resolution.** During indexing, table / column qualifiers are resolved cheaply through the
parser's alias map and uppercased for case-insensitive matching. An explicit schema in the SQL
(`SCHEMA.TABLE`) is preserved verbatim. Unqualified table references are resolved as part of the
index build against the live JDBC schema: exactly one match fills the schema, multiple matches are
marked `ambiguous`, and zero matches stay `unresolved`.

### Snapshot / Metadata Cache

Structural metadata (columns, keys, indexes, FKs, constraints, triggers) is cached in memory with a
TTL. This speeds up repeated calls to `schemaOverview`, `tableContext`, `findJoinPaths`,
`schemaLint`, `schemaGraph`, `queryContext`, and `describeTable`. Statistics tools such as
`tableStats`, `indexStats`, `columnStats`, and `sampleRows` are **not** cached; their counters are
live.

Configuration:

- `JDBC_METADATA_CACHE_TTL_SECONDS` - TTL in seconds. Default: `300`. Set to `0` to disable the cache.
- `JDBC_METADATA_CACHE_MAX_ENTRIES` - safety cap, default `2000`; when exceeded, the cache is cleared completely.

| Tool | Description |
|---|---|
| `getSchemaSnapshot` | Cache metadata: TTL, hit/miss counters, number of cached tables per schema (names only, not contents), and list-cache entries. Parameter: `schema` filter |
| `refreshSchemaSnapshot` | Invalidate and immediately warm the cache. With `table`, only one table is warmed; with `schema`, all tables in the schema are warmed through `describeTable`; with no arguments, the cache is fully cleared without warming. `maxTables` limits warming (default 300) |
| `invalidateSnapshot` | Targeted invalidation without warming: `table` for one table, `schema` for a whole schema, or no arguments for the entire cache |

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

## Error Format

All tools return errors in the same shape: JSON with `error` and `kind` fields.

```json
{"error": "Only SELECT / WITH / EXPLAIN statements are allowed", "kind": "rejected"}
```

| `kind` | When |
|---|---|
| `sql` | The database returned a `SQLException` for syntax, missing object, missing permission, and similar cases |
| `argument` | Invalid tool argument |
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
password.

1. **ReadOnlyGuard in project code.** Before sending SQL to the database, the server first parses it with JSqlParser and checks the AST. Only a single `SELECT`, `WITH`, or `EXPLAIN` is allowed. Write CTEs, `SELECT INTO`, and locking clauses such as `FOR UPDATE` are forbidden. If JSqlParser cannot parse dialect-specific SQL, the guard falls back to the older lexical check: first meaningful token, multi-statement rejection, comment skipping, and write-keyword detection outside strings and quoted identifiers.
2. **`connection.setReadOnly(true)`.** Set by Hikari and again by this server on each checkout.
3. **PostgreSQL: `default_transaction_read_only=on`.** Added to the JDBC URL automatically unless you already provided your own `options=`. Even server-side DDL is rejected.
4. **Oracle: JDBC read-only hint.** Oracle JDBC treats `setReadOnly(true)` mostly as an advisory hint. The client-side guard and a dedicated read-only database user are the primary Oracle protections. Oracle `EXPLAIN PLAN` writes a static plan to `PLAN_TABLE`; this server scopes those reads with a generated `STATEMENT_ID`.
5. **SQL Server: JDBC read-only hint plus SHOWPLAN estimated plans.** SQL Server also treats `setReadOnly(true)` as a hint. Use a least-privilege login/user for strong enforcement. `explainQuery` and `analyzePlan` use `SHOWPLAN_TEXT/XML`, which returns estimated plans without executing the statement.

### Maximum Protection: Use a Read-only Database User

If you can spend five minutes, create a dedicated user with read-only permissions. This is the
strongest guarantee even if the guard is accidentally disabled.

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

```text
JDBC_READONLY_GUARD=off
```

Connection-level protections (`setReadOnly` and, on PostgreSQL, `default_transaction_read_only`)
remain enabled. On Oracle and SQL Server, `setReadOnly` is best-effort; use a read-only database
user for the strongest guarantee.

## Stack

- Java 21, Spring Boot 4.0, Spring AI MCP 2.0.0-M5 (`stdio` transport)
- HikariCP through Spring Boot `starter-jdbc`
- PostgreSQL JDBC 42.7.4
- Oracle JDBC `ojdbc11` 23.6.0.24.10
- Microsoft SQL Server JDBC 12.8.1
- H2 in-memory runtime index for the local usage catalog
- Gradle 9.3.1 with version catalog

## License

This project is licensed under the Apache License, Version 2.0. See `LICENSE`.

Runtime and test dependencies are licensed by their respective owners. See
`THIRD_PARTY_NOTICES.md`, especially if you distribute a built fat jar containing bundled JDBC
drivers.

## Build

```bash
# Set JDK 21+ explicitly if it is not your default JDK:
export JAVA_HOME="$HOME/.jdks/jdk-21.0.6"

./gradlew build
```

Result: `build/libs/jdbc-mcp-server.jar` (includes PostgreSQL, Oracle, and SQL Server drivers).

### Integration Tests

Integration tests start real PostgreSQL, Oracle Free, and SQL Server instances through
Testcontainers, so Docker is required. They are excluded from the regular build and run separately:

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

## Configuration

| Variable | Required | Description |
|---|---|---|
| `JDBC_URL` | yes | JDBC URL, for example `jdbc:postgresql://host:5432/db`, `jdbc:oracle:thin:@//host:1521/service`, or `jdbc:sqlserver://host:1433;databaseName=db;encrypt=true;trustServerCertificate=false` |
| `JDBC_USERNAME` | yes | Database user, preferably read-only |
| `JDBC_PASSWORD` | yes | Password |
| `JDBC_DEFAULT_SCHEMA` | no | Default schema for metadata tools. If unset, the current connection schema is used |
| `JDBC_QUERY_TIMEOUT_SECONDS` | no | Per-query timeout, default `30` |
| `JDBC_MAX_ROWS` | no | Maximum rows in one response, default `1000`. If exceeded, the response includes `truncated: true` |
| `JDBC_FETCH_SIZE` | no | JDBC `fetchSize`, default `500` |
| `JDBC_READONLY_GUARD` | no | `strict` by default, or `off` |
| `JDBC_POOL_MAX_SIZE` | no | Hikari maximum pool size, default `40` |
| `JDBC_POOL_MIN_IDLE` | no | Hikari minimum idle connections, default `0`; with the default lazy pool settings, database connections are opened only when a tool first needs one |
| `JDBC_CONNECTION_TIMEOUT_MS` | no | Hikari connection checkout timeout in milliseconds, default `10000` |
| `JDBC_VALIDATION_TIMEOUT_MS` | no | Hikari validation timeout in milliseconds, default `5000` |
| `JDBC_POOL_IDLE_TIMEOUT_MS` | no | Hikari idle connection timeout in milliseconds, default `60000`; idle connections above `JDBC_POOL_MIN_IDLE` are closed after this |
| `JDBC_USAGE_CATALOG_ENABLED` | no | Toggle the local usage catalog (see *Usage Catalog* above), default `true`. When `false`, lookup tools return empty results with `catalog_enabled: false` |
| `JDBC_USAGE_CATALOG_PATHS` | no | Comma-separated directories, JSON files, or zip archives containing canonical QueryUsage JSON records |
| `JDBC_USAGE_NATIVE_SCHEMAS` | no | Comma-separated schemas to scan for native usage. When omitted, the resolved default schema is scanned |
| `JDBC_USAGE_NATIVE_INCLUDE_VIEWS` | no | Include views/materialized views in native usage, default `true` |
| `JDBC_USAGE_NATIVE_INCLUDE_ROUTINES` | no | Include functions/procedures in native usage, default `true` |
| `JDBC_USAGE_NATIVE_INCLUDE_TRIGGERS` | no | Include triggers in native usage, default `true` |
| `JDBC_USAGE_NATIVE_MAX_OBJECTS` | no | Maximum native usage records to add per index build, default `1000` |

The database type is detected automatically from the URL prefix: `jdbc:postgresql:` for PostgreSQL,
`jdbc:oracle:` for Oracle, and `jdbc:sqlserver:` for SQL Server.

### URL Examples

```text
jdbc:postgresql://db.example.com:5432/myapp
jdbc:postgresql://db.example.com:5432/myapp?currentSchema=public&sslmode=require

jdbc:oracle:thin:@//db.example.com:1521/ORCLPDB1
jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=...)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=...)))

jdbc:sqlserver://db.example.com:1433;databaseName=myapp;encrypt=true;trustServerCertificate=false
jdbc:sqlserver://db.example.com;databaseName=myapp;integratedSecurity=false
```

## Running

```bash
JDBC_URL=jdbc:postgresql://db.example.com:5432/myapp \
JDBC_USERNAME=ai_readonly \
JDBC_PASSWORD=secret \
  java -jar build/libs/jdbc-mcp-server.jar
```

The server immediately starts listening for MCP over stdin/stdout. Logs are written to stderr.

## Connecting an AI Client

Add this server to the client configuration:

```json
{
  "command": "java",
  "args": ["-jar", "<absolute-path>/jdbc-mcp-server.jar"],
  "env": {
    "JDBC_URL": "jdbc:postgresql://db.example.com:5432/myapp",
    "JDBC_USERNAME": "ai_readonly",
    "JDBC_PASSWORD": "secret"
  }
}
```

Where to configure it:

| Client | Connection method |
|---|---|
| Claude Code | `claude mcp add --scope user -e JDBC_URL=... -e JDBC_USERNAME=... -e JDBC_PASSWORD=... -- jdbc java -jar /path/to/jdbc-mcp-server.jar` |
| Qwen Code | `~/.qwen/settings.json` -> `"mcpServers"` -> `"jdbc"` |
| VS Code | `.vscode/mcp.json` -> `"servers"` -> `"jdbc"` |
| Cursor | `.cursor/mcp.json` -> `"mcpServers"` -> `"jdbc"` |
| Claude Desktop | `claude_desktop_config.json` -> `"mcpServers"` -> `"jdbc"` |

For Claude Code, omitting `--scope user` adds the server only to the current project.
Check the connection with `claude mcp list`. Restart the client after adding the server.

If you use different databases for different projects, add multiple servers with different keys,
such as `jdbc-pg`, `jdbc-oracle`, and `jdbc-mssql`, and different environment variable sets.

## Project Structure

```text
+-- src/main/java/ru/it_spectrum/ai/jdbc/mcp/
|   +-- JdbcMcpServerApplication.java   - Spring Boot entry point
|   +-- config/
|   |   +-- JdbcProperties.java         - connection settings from env
|   |   +-- DatabaseKind.java           - PG/Oracle/SQL Server autodetection from URL
|   |   +-- DataSourceConfig.java       - Hikari + connection-level read-only mode
|   +-- dialect/
|   |   +-- SqlDialect.java             - dialect interface
|   |   +-- PostgresDialect.java        - EXPLAIN, pg_catalog, pg_get_viewdef
|   |   +-- OracleDialect.java          - EXPLAIN PLAN, ALL_VIEWS, ALL_SOURCE, Oracle metadata queries
|   |   +-- SqlServerDialect.java       - SHOWPLAN, sys catalog metadata, SQL Server pagination
|   |   +-- DialectConfig.java          - implementation selection by DatabaseKind
|   +-- sql/
|   |   +-- ReadOnlyGuard.java          - JSqlParser AST guard + lexical fallback
|   |   +-- SqlNotAllowedException.java
|   |   +-- QueryResult.java            - result shape
|   |   +-- SqlExecutor.java            - query execution with limits
|   |   +-- BenchmarkService.java       - benchmark (cold+warm) and timed (+ pg_stat_statements diff)
|   +-- metadata/
|   |   +-- MetadataService.java        - DatabaseMetaData + dialect-specific metadata
|   |   +-- StatsService.java           - table/index stats, FK coverage, redundant/unused indexes
|   |   +-- DistributionService.java    - column distribution / histogram / null ratio / selectivity / join cardinality
|   |   +-- SchemaContextService.java   - high-level schema context: overview, table context, join paths, graph, lint, brief, query context
|   +-- plan/
|   |   +-- ParsedPlan.java / PlanNode.java - unified engine-agnostic plan model
|   |   +-- PlanParser.java             - parser interface
|   |   +-- PostgresPlanParser.java     - JSON EXPLAIN -> tree
|   |   +-- OraclePlanParser.java       - PLAN_TABLE -> tree
|   |   +-- SqlServerPlanParser.java    - SHOWPLAN_XML -> tree
|   |   +-- PlanAnalyzer.java           - summary: expensive / full scan / estimate error / nested loop / spill
|   |   +-- JsonReader.java             - small dependency-free JSON parser
|   +-- usage/
|   |   +-- UsageProperties.java        - catalog enable flag and JSON/zip source paths
|   |   +-- UsageDataSourceConfig.java  - H2 in-memory runtime index + schema init
|   |   +-- UsageUid.java               - build/parse/validate the textual query identifier
|   |   +-- UsageCatalogService.java    - ingest, lookups, observed-relationships aggregation
|   |   +-- format/
|   |   |   +-- QueryUsage.java         - canonical query usage record DTO
|   +-- tools/
|       +-- QueryTools.java             - executeQuery, explainQuery, analyzePlan, validateQuery, inspectQuery, queryLint, resolveQueryLineage
|       +-- MetadataTools.java          - schemas / tables / describe / view / routines / sequences / search
|       +-- SampleTools.java            - sampleRows
|       +-- DistributionTools.java      - columnStats, columnDistribution, columnHistogram, nullRatio, estimateSelectivity, joinCardinality
|       +-- StatsTools.java             - tableStats, indexStats, unusedIndexes, redundantIndexes, fkIndexCoverage
|       +-- BenchmarkTools.java         - benchmarkQuery, timedQuery
|       +-- SchemaContextTools.java     - schemaOverview, tableContext, findJoinPaths, schemaLint, schemaBrief, schemaGraph, queryContext, schemaGraphDot
|       +-- UsageTools.java             - usageCatalogStatus, invalidateUsageCatalogCache, getQuery, listQueries, findQueriesBy(Table|Column), observedRelationships, listKnownTags/Domains
+-- src/main/resources/
    +-- application.yml                 - MCP stdio + JDBC properties
    +-- usage-catalog-schema.sql        - DDL for the H2 in-memory usage-catalog runtime index
    +-- logback-spring.xml              - logs to stderr because stdout is used by MCP
```

## Troubleshooting

- **"Cannot find a Java installation ... matching languageVersion=21"** - install JDK 21+ and set `JAVA_HOME`. Gradle toolchains cannot download it without internet access.
- **Connection refused / ORA-01017 / FATAL / SQL Server login failed** - check `JDBC_URL`, `JDBC_USERNAME`, and `JDBC_PASSWORD`. For PostgreSQL, test with `psql "$JDBC_URL"`; for Oracle, use `sqlplus $JDBC_USERNAME/$JDBC_PASSWORD@...`; for SQL Server, test with `sqlcmd -S host,1433 -d database -U user -P password`.
- **`{"kind":"rejected","error":"Only SELECT / WITH / EXPLAIN statements are allowed"}`** - the guard worked. This is expected for any write operation. If the query is truly read-only, for example a read-only function call through `SELECT func(...)`, it will pass. For fully non-trivial cases, you can disable the guard with `JDBC_READONLY_GUARD=off`.
- **Oracle write attempt reached the database** - this should normally be blocked by the guard first. If `JDBC_READONLY_GUARD=off`, rely on a read-only Oracle user; JDBC `setReadOnly(true)` is only a best-effort hint for Oracle.
- **Empty `describeTable` / `listTables` result on Oracle** - Oracle stores object names in uppercase. Pass `CUSTOMERS`, not `customers`.
- **SQL Server certificate errors** - set the JDBC URL encryption options explicitly, for example `encrypt=true;trustServerCertificate=false` with a trusted certificate, or `trustServerCertificate=true` only for local/dev use.
- **SQL Server `unusedIndexes` unsupported** - this tool intentionally avoids `sys.dm_db_index_usage_stats` because it usually requires elevated state-view permissions. Use `indexStats`, `fkIndexCoverage`, and `redundantIndexes` for low-privilege SQL Server audits.
