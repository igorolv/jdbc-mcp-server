# Supported Databases

Built-in dialects for PostgreSQL, Oracle, SQL Server, Firebird and SQLite, with their drivers
bundled; any other database with a JDBC driver is served in generic mode. This page covers what each
engine supports, URL examples, and per engine how read-only is enforced, how names are matched,
where plans and statistics come from, and what the database user needs. Firebird, SQLite and
generic JDBC differ the most from what you may expect — read their notes before using them.

- [Capabilities](#capabilities)
- [URL examples](#url-examples)
- [PostgreSQL](#postgresql)
- [Oracle](#oracle)
- [SQL Server](#sql-server)
- [Firebird](#firebird)
- [SQLite](#sqlite)
- [Generic JDBC](#generic-jdbc)

## Capabilities

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

## URL examples

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
MySQL / MariaDB / H2 / Db2 — are in the [connections guide](connections.md#recipes-per-engine).

## PostgreSQL

PostgreSQL 11 and later (the integration tests run on 16), through the bundled pgjdbc driver.

- **Read-only inside the database.** The server appends
  `options=-c default_transaction_read_only=on` to the URL, so every transaction of the session is
  read-only in PostgreSQL itself and even DDL is rejected. A URL that sets its own `options=` is
  left untouched and loses this protection; add the setting to your `options` value yourself — see
  the [PostgreSQL recipe](connections.md#postgresql).
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
  [read-only role snippet](../README.md#maximum-protection-use-a-read-only-database-user).

## Oracle

Oracle Database 12c and later (the integration tests run on 23ai Free), through the bundled
`ojdbc11` driver.

- **Read-only is up to the guard and the user.** The Oracle driver treats `setReadOnly(true)` as a
  hint. The guard lets only `SELECT` / `WITH` / `EXPLAIN` through; a
  [read-only user](../README.md#maximum-protection-use-a-read-only-database-user) is the real protection.
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

## SQL Server

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
  [login snippet](../README.md#maximum-protection-use-a-read-only-database-user).

## Firebird

Firebird 3.0 and later, through Jaybird 6 (bundled). Connect over the network with the pure-Java
driver — `jdbc:firebirdsql://<host>:3050//<path/to/db.fdb>` — to a Firebird server; no native
client library is needed. The bundled `jaybird-native` module also supports opening a local
`.fdb` / `.gdb` file in-process:

```json
"local-firebird": {
  "url": "jdbc:firebirdsql:embedded:C:/data/app.gdb?nativeLibraryPath=C:/Firebird/Firebird_5_0",
  "username": "SYSDBA",
  "password": "<pw>"
}
```

`nativeLibraryPath` names a directory containing the matching `fbclient.dll` (Windows) or
`libfbclient.so` (Linux), not a JDBC jar. Firebird 3+ also needs its matching engine and plugins
beside that library. A RED Database file needs the RED Database native installation; Jaybird alone
does not implement the database file format. The native library is selected on the first native or
embedded connection in a JVM, so serve databases requiring different native installations from
separate MCP processes. Embedded access may change database header/transaction pages even for
read-only SQL; use a copy when the original file must stay unchanged.

Alternatively, an embedded database can be served by starting a Firebird server of the matching
version on a **copy** of the file (Firebird 3 for ODS 12, Firebird 4/5 for ODS 13); the official
`firebirdsql/firebird` Docker image works:

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

## SQLite

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

## Generic JDBC

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
