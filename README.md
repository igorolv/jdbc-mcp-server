# JDBC MCP Server

A local MCP server for read-only access to PostgreSQL, Oracle, and Microsoft SQL Server databases.
It lets AI agents such as Claude Code, Cursor, VS Code Copilot, and others write SQL queries,
inspect execution plans, and explore database structure: tables, columns, indexes, foreign keys,
views, functions, and sequences.

PostgreSQL, Oracle, and Microsoft SQL Server JDBC drivers are bundled into the fat jar, so no
extra driver installation is required.

The server exposes 49 MCP tools and can optionally expose catalog-qualified MCP resources for table
and column metadata. Tools may update the local SQLite catalog, but they never write to the inspected
PostgreSQL, Oracle, or SQL Server database.

One server process can serve several databases: name them in
[`connections.json`](#serving-several-databases-from-one-server) and pass `connection` to any tool.
The tool manifest stays a single set regardless of how many databases are configured, and pools are
opened only for the databases actually used.

## Why This Exists

Scenario: you ask an LLM to "check the database and show how many orders we had by status last
month." Without this server, the LLM may:

- invent table and column names;
- miss the real schema details, such as nullable fields, types, and foreign keys;
- accidentally generate a `DELETE` or `TRUNCATE` while "reasoning."

With this server, the LLM can:

1. call `schemaBrief` to discover the schema map, or `queryContext` to get ready-to-use detailed context: tables, columns, relationships, and constraints;
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
                                                  +------------+
                                            +---> | database A |
+-------------+     stdio      +----------+ |     +------------+
|  AI agent   | <------------> | jdbc-mcp | |     +------------+
| (Claude Code|  stdin/stdout  |  server  |-+---> | database B |
|  Cursor...) |                |  (Java)  | |     +------------+
+-------------+                +----------+ |
                                            +---> ...

                                          read-only JDBC
                                          PG / Oracle / SQL Server
```

The protocol is `stdio` only. The client starts the server as a child process. One process serves
one database by default and any number of named ones when a
[connections file](#serving-several-databases-from-one-server) is present; a connection's pool is
opened the first time a tool call names it.

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

## MCP Tools

The 49 tools are grouped below by purpose.

Every tool takes `connection` as its **first, required** argument, naming the database to run
against — including installations that serve exactly one database. `listConnections` lists the
names. See [Serving Several Databases from One Server](#serving-several-databases-from-one-server).

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

**Why this exists.** The metadata tools answer "what tables and columns exist". The usage catalog
answers "how are they actually used by applications". With both, an LLM can replace guesses about
undeclared joins with evidence-based reasoning ("these two columns are joined in 17 production
reports, here are their uids").

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

Both are per-connection fields in
[`connections.json`](#serving-several-databases-from-one-server).

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

```json
"readonlyGuard": "off"
```

Connection-level protections (`setReadOnly` and, on PostgreSQL, `default_transaction_read_only`)
remain enabled. On Oracle and SQL Server, `setReadOnly` is best-effort; use a read-only database
user for the strongest guarantee.

## Stack

- Java 21, Spring Boot 4.0, Spring AI MCP 2.0.0-M6 (`stdio` transport)
- HikariCP through Spring Boot `starter-jdbc`
- PostgreSQL JDBC 42.7.4
- Oracle JDBC `ojdbc11` 23.6.0.24.10
- Microsoft SQL Server JDBC 12.8.1
- SQLite 3.51.3 WAL catalog (`<catalog>.db`) holding the usage index and persistent structure snapshot
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

Databases, credentials and everything that varies per database live in
[`connections.json`](#serving-several-databases-from-one-server). The environment configures only
the server process itself:

| Variable | Required | Description |
|---|---|---|
| `JDBC_MCP_CONNECTIONS_FILE` | no | Path of the JSON file describing the named connections this server serves; default `<data-dir>/connections.json`. Startup fails when the file is missing or defines no connection |
| `JDBC_MCP_DATA_DIR` | no | Root directory for server-local data, default `~/.jdbc-mcp-server`. Each connection gets its own subdirectory under it |
| `JDBC_MCP_RESOURCES_ENABLED` | no | Expose the catalog-qualified manifest plus concrete table resources and table/column resource templates; default `false` |
| `JDBC_MCP_TOOLS_*` | no | Per-group tool toggles that control which tools appear in `tools/list`. All groups default to `true`; set a group to `false` to hide it (useful for small-context models). See [Tool Groups](#tool-groups) |

A connection's own settings — URL, credentials, default schema, timeouts, row caps, pool sizes, the
read-only guard, snapshot and usage options — are fields of its `connections.json` entry; see
[the field table](#serving-several-databases-from-one-server). Any of them may reference an
environment variable as `${VAR}`, so secrets stay out of the file.

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

Describe at least one database in `~/.jdbc-mcp-server/connections.json`:

```json
{
  "connections": {
    "myapp": {
      "url": "jdbc:postgresql://db.example.com:5432/myapp",
      "username": "ai_readonly",
      "password": "${MYAPP_DB_PASSWORD}"
    }
  }
}
```

then run:

```bash
java -jar build/libs/jdbc-mcp-server.jar
```

The server immediately starts listening for MCP over stdin/stdout. Logs are written to stderr.
Tool calls address this database as `"connection": "myapp"`.

## Connecting an AI Client

Add this server to the client configuration:

```json
{
  "command": "java",
  "args": ["-jar", "<absolute-path>/jdbc-mcp-server.jar"],
  "env": {
    "MYAPP_DB_PASSWORD": "secret"
  }
}
```

The databases come from `connections.json`; `env` only carries the secrets its `${VAR}` references
point at (and, if you keep the file elsewhere, `JDBC_MCP_CONNECTIONS_FILE`).

### Where to Configure It

| Client | Connection method |
|---|---|
| Claude Code | `claude mcp add --scope user -e MYAPP_DB_PASSWORD=... -- jdbc java -jar /path/to/jdbc-mcp-server.jar` |
| Qwen Code | `~/.qwen/settings.json` -> `"mcpServers"` -> `"jdbc"` |
| VS Code | `.vscode/mcp.json` -> `"servers"` -> `"jdbc"` |
| Cursor | `.cursor/mcp.json` -> `"mcpServers"` -> `"jdbc"` |
| Claude Desktop | `claude_desktop_config.json` -> `"mcpServers"` -> `"jdbc"` |

For Claude Code, omitting `--scope user` adds the server only to the current project.
Check the connection with `claude mcp list`. Restart the client after adding the server.

## Serving Several Databases from One Server

One server process can serve any number of named databases. The tool manifest stays a single set of
49 tools no matter how many are configured — each tool takes `connection` as its first argument —
and a database's pool, local catalog and services are created the first time something actually asks
for that connection.

This matters at scale: registering fifteen MCP server instances puts fifteen tool manifests into the
agent's context and fifteen JVMs in memory, when the session may end up touching two of the
databases.

### `connections.json`

Default path `~/.jdbc-mcp-server/connections.json` (`<data-dir>/connections.json`), overridden with
`JDBC_MCP_CONNECTIONS_FILE`:

```json
{
  "connections": {
    "orders": {
      "url": "jdbc:postgresql://db.example.com:5432/orders",
      "username": "ai_readonly",
      "password": "${ORDERS_DB_PASSWORD}",
      "defaultSchema": "public",
      "description": "Order service — customers, orders, shipments",
      "structureSnapshotSchemas": ["public", "nsi"]
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

The object key is the connection name. It is also the name of the connection's local catalog
directory (`<data-dir>/<name>/`) and appears in MCP resource URIs, so it must match
`[A-Za-z0-9._-]{1,64}`.

`url` is the only required field. `description` is free text returned by `listConnections`, so an
agent can pick a database by meaning rather than by name — worth filling in.

Any string value may reference an environment variable as `${VAR}`. A referenced variable that is
not set fails startup with a message naming the variable and the field; it never becomes an empty
password.

Everything else is optional; a field left out falls back to the built-in default:

| Field | Default | Meaning |
|---|---|---|
| `username`, `password` | none | Database credentials; prefer `${VAR}` references |
| `description` | none | Free text returned by `listConnections` |
| `defaultSchema` | the session schema | Schema used when a metadata tool call omits one |
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

The `JDBC_MCP_TOOLS_*` group flags stay in the environment — they shape the manifest, which is
shared by all connections.

> **Keep the file private.** It holds database credentials: `chmod 600 ~/.jdbc-mcp-server/connections.json`
> (on Windows, restrict it to your user). Prefer `${VAR}` references over literal passwords so the
> secrets live in the environment and the file can be reviewed, copied, or backed up safely.

### Choosing a connection

There is no default connection: every tool call names the database it means in its first argument.
A missing or unknown name returns an `argument` error listing the available names. Call
`listConnections` to see what exists — it reads configuration only, so it works even when some of
the configured databases are down.

### A single database

Nothing changes for one database: write a `connections.json` with a single entry and pass its name
as `connection`. There is no environment-variable shortcut — one file is the whole configuration.

### Isolation

- Configuring a connection costs nothing until it is used: no pool, no catalog file, no connection.
- Reaching database `X` opens pools for `X` only.
- A database that is down, or an entry whose URL is not a supported JDBC URL, fails the calls made
  against it and leaves the other connections working. `listConnections` reports the reason in
  `configError`.
- Each connection keeps its own local catalog at `<data-dir>/<name>/<name>.db`, so structure
  snapshots and usage indexes never mix.
- MCP resources (when `JDBC_MCP_RESOURCES_ENABLED=true`) are published for every configured
  connection that already has a local catalog file; URIs were catalog-qualified already.

Each connection keeps its logs under `<data-dir>/<name>/logs/`.

### One instance per database (the earlier approach)

Registering one server instance per database still works and remains a reasonable choice for one or
two databases. The client namespaces tools by server key, at the cost of one tool manifest and one
JVM per database:

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
snapshot would share one `<catalog>.db` file.

## Project Structure

```text
+-- src/main/java/ru/it_spectrum/ai/jdbc/mcp/
|   +-- JdbcMcpServerApplication.java   - Spring Boot entry point
|   +-- config/
|   |   +-- JdbcProperties.java         - connection settings from env
|   |   +-- JdbcMcpProperties.java      - local data directory and catalog name
|   |   +-- UsageProperties.java        - usage-catalog sources and native-object settings
|   |   +-- StructureSnapshotProperties.java - schemas captured by rebuildCatalog
|   |   +-- DatabaseKind.java           - PG/Oracle/SQL Server autodetection from URL
|   |   +-- DataSourceConfig.java       - Hikari pool builder + connection-level read-only mode
|   |   +-- ConnectionsConfig.java      - global defaults and the connection registry bean
|   +-- connection/
|   |   +-- ConnectionsFile.java        - connections.json shape
|   |   +-- ConnectionsLoader.java      - file + env defaults -> connection definitions
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
|   |   +-- PlanAnalyzer.java           - summary: expensive / full scan / estimate error / nested loop / spill
|   +-- usage/
|   |   +-- CatalogDataSourceConfig.java - SQLite WAL datasource + schema init
|   |   +-- CatalogStorageService.java  - WAL checkpoint for distributable catalogs
|   |   +-- UsageCatalogService.java    - ingest, lookups, observed-relationships aggregation
|   |   +-- format/
|   |   |   +-- QueryUsage.java         - canonical query usage record DTO
|   +-- tools/
|       +-- QueryTools.java             - executeQuery, explainQuery, analyzePlan, validateQuery, inspectQuery, queryLint, resolveQueryLineage
|       +-- MetadataTools.java          - schemas / tables / describe / view / routines / sequences / search
|       +-- AdminTools.java             - rebuildCatalog (build structure snapshot + usage index into a distributable <catalog>.db)
|       +-- SampleTools.java            - sampleRows
|       +-- DistributionTools.java      - columnStats, columnDistribution, columnHistogram, nullRatio, estimateSelectivity, joinCardinality
|       +-- StatsTools.java             - tableStats, indexStats, unusedIndexes, redundantIndexes, fkIndexCoverage
|       +-- BenchmarkTools.java         - benchmarkQuery, timedQuery
|       +-- SchemaContextTools.java     - schemaBrief, tableContext, findJoinPaths, schemaLint, schemaGraph, queryContext, schemaGraphDot
|       +-- UsageTools.java             - usageCatalogStatus, invalidateUsageCatalogCache, getQuery, listQueries, findQueriesBy(Table|Column), observedRelationships, listKnownTags/Domains/Kinds
+-- src/main/resources/
    +-- application.yml                 - MCP stdio + JDBC properties
    +-- usage-catalog-schema.sql        - DDL for the usage-catalog index (in <catalog>.db)
    +-- structure-snapshot-schema.sql   - DDL for the persistent structure snapshot (in <catalog>.db)
    +-- logback-spring.xml              - logs to stderr because stdout is used by MCP
```

## Troubleshooting

- **"Cannot find a Java installation ... matching languageVersion=21"** - install JDK 21+ and set `JAVA_HOME`. Gradle toolchains cannot download it without internet access.
- **Connection refused / ORA-01017 / FATAL / SQL Server login failed** - check the connection's `url`, `username`, and `password` in `connections.json`. For PostgreSQL, test the URL with `psql`; for Oracle, use `sqlplus user/password@...`; for SQL Server, test with `sqlcmd -S host,1433 -d database -U user -P password`.
- **`{"kind":"rejected","error":"Only SELECT / WITH / EXPLAIN statements are allowed"}`** - the guard worked. This is expected for any write operation. If the query is truly read-only, for example a read-only function call through `SELECT func(...)`, it will pass. For fully non-trivial cases, you can disable the guard with `"readonlyGuard": "off"` on that connection.
- **Oracle write attempt reached the database** - this should normally be blocked by the guard first. If `readonlyGuard` is `off`, rely on a read-only Oracle user; JDBC `setReadOnly(true)` is only a best-effort hint for Oracle.
- **Empty `describeTable` / `listTables` result on Oracle** - Oracle stores object names in uppercase. Pass `CUSTOMERS`, not `customers`.
- **SQL Server certificate errors** - set the JDBC URL encryption options explicitly, for example `encrypt=true;trustServerCertificate=false` with a trusted certificate, or `trustServerCertificate=true` only for local/dev use.
- **SQL Server `unusedIndexes` unsupported** - this tool intentionally avoids `sys.dm_db_index_usage_stats` because it usually requires elevated state-view permissions. Use `indexStats`, `fkIndexCoverage`, and `redundantIndexes` for low-privilege SQL Server audits.
