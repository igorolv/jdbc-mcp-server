# Configuring Connections — the Detailed Guide

The [README](../README.md#configuring-connections) covers what most setups need: where
`connections.json` lives, the fields, and one example per engine. This page is the reference behind
it — recipes per engine, environments and naming, secrets, external drivers, Docker, tuning,
checking a configuration, and what every configuration error means.

- [Where the file lives](#where-the-file-lives)
- [A fully annotated entry](#a-fully-annotated-entry)
- [Recipes per engine](#recipes-per-engine)
- [Several databases and environments](#several-databases-and-environments)
- [Secrets](#secrets)
- [External drivers](#external-drivers)
- [Running in Docker](#running-in-docker)
- [Tuning a connection](#tuning-a-connection)
- [Checking a configuration](#checking-a-configuration)
- [Changing a configuration](#changing-a-configuration)
- [Configuration errors](#configuration-errors)

## Where the file lives

| Setup | Default path |
|---|---|
| Linux / macOS | `~/.jdbc-mcp-server/connections.json` |
| Windows | `%USERPROFILE%\.jdbc-mcp-server\connections.json` |
| Docker image | `/data/connections.json` (mount a host directory at `/data`) |

The file sits in the **data directory**, which also holds one subdirectory per connection (local
catalog, usage-catalog sources) and the shared log:

```text
~/.jdbc-mcp-server/
├── connections.json
├── drivers/                      ← optional: jars for generic JDBC connections
├── logs/jdbc-mcp-server.log
├── orders/
│   ├── orders.db                 ← structure snapshot + usage index (SQLite, WAL)
│   └── usage-catalog/            ← QueryUsage JSON files picked up automatically
└── billing/
    └── ...
```

Two environment variables move things around: `JDBC_MCP_DATA_DIR` moves the whole directory, and
`JDBC_MCP_CONNECTIONS_FILE` points at a connections file elsewhere while catalogs and logs stay in
the data directory. Both accept a leading `~`.

## A fully annotated entry

Only `url` is required. Everything else is shown here with its default value, apart from the fields
that have none:

```jsonc
{
  "connections": {
    "orders@prod": {                          // name: [A-Za-z0-9._-]+ with an optional @stand, ≤ 64 chars
      "url": "jdbc:postgresql://db.example.com:5432/orders?sslmode=require",
      "username": "ai_readonly",
      "password": "${ORDERS_PROD_PASSWORD}",  // ${VAR} works in any string value
      "description": "Order service, PRODUCTION — customers, orders, shipments",

      "defaultSchema": "public",              // default: the session's current schema
      "queryTimeoutSeconds": 30,              // 0 disables
      "maxRows": 1000,                        // responses say truncated: true when hit
      "fetchSize": 500,
      "readonlyGuard": "strict",              // "off" disables the client-side SELECT-only check

      "poolMaximumSize": 40,
      "poolMinimumIdle": 0,                   // 0 = no connection until the first tool call
      "poolConnectionTimeoutMs": 10000,
      "poolValidationTimeoutMs": 5000,
      "poolIdleTimeoutMs": 60000,

      "structureSnapshotSchemas": ["public", "nsi"],   // what rebuildCatalog captures; default: defaultSchema
      "structureSnapshotOracleColumnQueryTimeoutSeconds": 300,

      "usageCatalogEnabled": true,
      "usageCatalogPaths": ["/srv/reports/usage.zip"], // in addition to <data-dir>/<name>/usage-catalog
      "usageNativeSchemas": ["public"],                // default: defaultSchema
      "usageNativeIncludeViews": true,
      "usageNativeIncludeRoutines": true,
      "usageNativeIncludeTriggers": true,
      "usageNativeMaxObjects": 10000,

      "dialect": "postgresql",                // default: detected from the URL
      "driverPath": "drivers/postgresql-42.7.5.jar",   // default: the bundled driver
      "driverClass": "org.postgresql.Driver"  // default: the driver that accepts the URL
    }
  }
}
```

The real file is plain JSON, so drop the comments. **Unknown keys are ignored without a warning**:
a misspelled `maxRow` or `querytimeoutSeconds` leaves the default in force. Compare field names with
the [field table](../README.md#connection-fields) when a setting does not seem to take effect.

## Recipes per engine

### PostgreSQL

```json
"orders": {
  "url": "jdbc:postgresql://db.example.com:5432/orders?sslmode=verify-full&sslrootcert=/etc/ssl/db-ca.pem",
  "username": "ai_readonly",
  "password": "…",
  "defaultSchema": "app",
  "structureSnapshotSchemas": ["app", "ref"],
  "usageNativeSchemas": ["app", "ref"]
}
```

- The server appends `options=-c default_transaction_read_only=on`, which makes every transaction of
  the session read-only on the server side. **If your URL already has an `options=` parameter, the
  server leaves the URL alone and this protection is gone.** Put the setting into your own
  `options` value: `options=-c%20search_path%3Dapp%20-c%20default_transaction_read_only%3Don`.
  To set the search path alone, `currentSchema=app` is simpler.
- `sslmode=require` encrypts without verifying the server. `verify-full` with `sslrootcert` also
  checks the certificate and host name.
- `timedQuery` reports `pg_stat_statements` deltas when that extension is installed in the database.

### Oracle

```json
"billing": {
  "url": "jdbc:oracle:thin:@//oracle.example.com:1521/BILLING",
  "username": "AI_READONLY",
  "password": "…",
  "defaultSchema": "BILLING_OWNER",
  "structureSnapshotSchemas": ["BILLING_OWNER"]
}
```

| URL form | When |
|---|---|
| `jdbc:oracle:thin:@//host:1521/SERVICE` | Service name (the usual case) |
| `jdbc:oracle:thin:@host:1521:SID` | Legacy SID |
| `jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=…)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=…)))` | Full descriptor, e.g. copied from `tnsnames.ora` |
| `jdbc:oracle:thin:@ALIAS?TNS_ADMIN=/path/to/tns` | A `tnsnames.ora` alias |

- A read-only user rarely owns the application tables. Set `defaultSchema` to the owning schema, in
  upper case, or every metadata call has to name it.
- `rebuildCatalog` reads columns in bulk through `DBMS_XMLGEN`. That query is slow on large
  dictionaries, so it gets its own `structureSnapshotOracleColumnQueryTimeoutSeconds` (300 s) rather
  than `queryTimeoutSeconds`.
- A newer `ojdbc` than the bundled one: `"driverPath": "drivers/ojdbc17.jar", "dialect": "oracle"`.

### SQL Server

```json
"crm": {
  "url": "jdbc:sqlserver://sql.example.com:1433;databaseName=crm;encrypt=true;trustServerCertificate=false",
  "username": "ai_readonly",
  "password": "…",
  "defaultSchema": "dbo"
}
```

- Named instance: `jdbc:sqlserver://sql.example.com;instanceName=SQLEXPRESS;databaseName=crm`. The
  `host\INSTANCE` form works too, but inside JSON the backslash has to be doubled:
  `sql.example.com\\SQLEXPRESS`.
- `trustServerCertificate=true` switches off certificate checks. Use it only on a local or dev server.
- `GRANT SHOWPLAN` lets `explainQuery` and `analyzePlan` work; see the
  [read-only user snippets](../README.md#maximum-protection-use-a-read-only-database-user).

### Firebird

```json
"legacy": {
  "url": "jdbc:firebirdsql://fb.example.com:3050//var/lib/firebird/data/app.fdb?encoding=WIN1251",
  "username": "SYSDBA",
  "password": "…"
}
```

No `defaultSchema`: the database is one logical schema, `PUBLIC`. Without an `encoding=` /
`charSet=` / `lc_ctype=` parameter the server adds `encoding=UTF8`. The README section on
[Firebird](../README.md#firebird) explains how to serve an embedded `.fdb` / `.gdb` file with Docker.

### SQLite

```json
"analytics": { "url": "jdbc:sqlite:/srv/data/analytics.db" }
```

- Use an **absolute path**. A relative one resolves against the working directory of the server
  process, which the MCP client picks.
- On Windows use forward slashes (`jdbc:sqlite:C:/data/app.db`) or doubled backslashes
  (`jdbc:sqlite:C:\\data\\app.db`).
- The file is opened with `open_mode=1` (read-only). A missing file is an error on first use; the
  server never creates an empty database. Keep your own `open_mode` out of the URL unless you know
  why you need it: setting it replaces the server's read-only flag.
- No credentials, no `defaultSchema` (the schema is `main`).

### Other databases (generic JDBC)

Put the driver jar next to the connections file and point `driverPath` at it. A relative path is
resolved against the directory of `connections.json`.

```json
"shop-mysql": {
  "url": "jdbc:mysql://mysql.example.com:3306/shop",
  "username": "ai_readonly",
  "password": "…",
  "driverPath": "drivers/mysql-connector-j-9.1.0.jar",
  "description": "Web shop (MySQL)"
}
```

| Database | URL shape | Driver |
|---|---|---|
| MySQL | `jdbc:mysql://host:3306/db` | MySQL Connector/J (`mysql-connector-j-*.jar`) |
| MariaDB | `jdbc:mariadb://host:3306/db` | MariaDB Connector/J (`mariadb-java-client-*.jar`) |
| H2 (server) | `jdbc:h2:tcp://host/~/db` | `h2-*.jar` |
| H2 (file) | `jdbc:h2:/path/db;ACCESS_MODE_DATA=r` | `h2-*.jar` (`ACCESS_MODE_DATA=r` opens the file read-only) |
| IBM Db2 | `jdbc:db2://host:50000/DBNAME` | `jcc-*.jar` |
| HSQLDB | `jdbc:hsqldb:hsql://host/db` | `hsqldb-*.jar` |
| Apache Derby (network) | `jdbc:derby://host:1527/db` | `derbyclient-*.jar` (plus `derbyshared-*.jar` on 10.15+) |

Generic mode protects against writes only on a best-effort basis: the guard, plus
`setReadOnly(true)` where the driver honours it. Pair it with a read-only database user, or a
read-only URL option such as H2's `ACCESS_MODE_DATA=r`. See [Generic JDBC](../README.md#generic-jdbc)
for what works and what does not.

## Several databases and environments

### Naming

A connection name is what the agent types in every call and what it sees in `listConnections`. It
is also a directory name, so pick it once and keep it. The optional `@` separates the service from
the stand:

```json
{
  "connections": {
    "orders@dev":  { "url": "jdbc:postgresql://dev-db:5432/orders",  "description": "Order service, DEV — safe to explore" },
    "orders@tst":  { "url": "jdbc:postgresql://tst-db:5432/orders",  "description": "Order service, TEST — refreshed from prod weekly" },
    "orders@prod": { "url": "jdbc:postgresql://prod-db:5432/orders", "description": "Order service, PRODUCTION — keep queries small",
                     "maxRows": 200, "queryTimeoutSeconds": 15, "poolMaximumSize": 4 },
    "billing@prod": { "url": "jdbc:oracle:thin:@//ora:1521/BILLING", "description": "Legacy billing (Oracle), PRODUCTION" }
  }
}
```

Dashes cannot mark the stand, because service names contain them already (`ssj-ek-export-dev` is
ambiguous). `@` never appears in a service name.

### Writing descriptions

`description` is the only thing that tells an agent what a database is *for*. A good one names the
system, the stand, and anything the agent should respect: "Order service, PRODUCTION — keep queries
small", "Warehouse replica, lags ~15 min", "Legacy billing, read-only archive since 2024".

### One file per project

Register the server per project and give each project its own file through the client's config,
for example a project-scoped `.mcp.json`:

```json
{
  "mcpServers": {
    "jdbc": {
      "command": "java",
      "args": ["-jar", "/opt/jdbc-mcp-server.jar"],
      "env": { "JDBC_MCP_CONNECTIONS_FILE": "/home/me/.jdbc-mcp-server/projects/shop.json" }
    }
  }
}
```

Catalogs still live in the shared data directory and are keyed by connection name alone. **Two files
that both define `orders` share one `orders/orders.db`**, even if they point at different databases.
Give such connections distinct names (`orders@shop`, `orders@crm`), or give each project its own
`JDBC_MCP_DATA_DIR`.

## Secrets

### `${VAR}` placeholders

Any string value, not only `password`, may contain one or more `${VAR}` references, for example
`"jdbc:postgresql://${DB_HOST}:5432/orders"`. The rules:

- A variable that is not set **fails startup** with a message naming the variable and the field. It
  never turns into an empty password.
- Numbers and booleans (`maxRows`, `usageCatalogEnabled`, …) are JSON values, not strings, and
  cannot be placeholders.
- There is no default syntax (`${VAR:-x}`) and no escape for a literal `${`.

### Where the variables should come from

The point of the connections file is to keep credentials away from the material an agent reads
anyway: the MCP client's config and the shell environment (see
[the reasoning](../README.md#why-credentials-live-in-a-file-not-in-environment-variables)). Setting
`${VAR}` from the client's `env` block undoes that. Use placeholders when the value comes from
somewhere the agent does not look, such as a systemd unit, a secret manager, or a wrapper script
registered as the MCP command:

```bash
#!/bin/sh
# /usr/local/bin/jdbc-mcp — register this as the MCP server command
export BILLING_DB_PASSWORD="$(secret-tool lookup service billing-db)"
exec java -jar /opt/jdbc-mcp-server.jar
```

The other good reason for a placeholder is a connections file that is shared or kept in version
control while the secrets must not be.

### File permissions

Linux / macOS:

```bash
chmod 600 ~/.jdbc-mcp-server/connections.json
```

Windows (PowerShell): remove inherited access and grant only yourself:

```powershell
icacls "$env:USERPROFILE\.jdbc-mcp-server\connections.json" /inheritance:r /grant:r "${env:USERNAME}:(F)"
```

Either way this keeps out *other users*. A process running as you, including an agent with shell
access, can still read the file. The guarantee that survives that is a read-only database user.

## External drivers

`driverPath` loads a JDBC driver that is not bundled. The same mechanism also replaces a bundled one.

- **A jar or a directory.** For a directory, every `*.jar` in it (not in subdirectories) is loaded,
  in name order. Use a directory for drivers split across several jars, such as Db2 with its license
  jar or Derby's client plus shared jar.
- **Relative paths** are resolved against the directory of `connections.json`; a leading `~` is the
  home directory. A `drivers/` directory next to the file keeps the whole setup relocatable.
- **Isolation.** Each connection's jars get their own class loader, whose parent is the platform
  class loader. They cannot see, or clash with, the bundled drivers or the server's own libraries.
  Two connections can use two versions of one driver.
- **Which driver.** By default the server takes the driver from the jars that accepts the URL.
  `driverClass` names it explicitly, which old drivers without a `META-INF/services` registration
  need.
- **Which dialect.** A URL that no built-in dialect recognizes becomes generic JDBC. A URL a built-in
  dialect does recognize keeps that dialect even with `driverPath` (a newer Oracle driver still gets
  the Oracle dialect). `"dialect": "generic"` forces generic mode.

## Running in Docker

```bash
docker run -i --rm -v ~/.jdbc-mcp-server:/data ghcr.io/igorolv/jdbc-mcp-server:latest
```

- `/data` is the data directory, so `connections.json`, `drivers/` and the catalogs all come from the
  mounted host directory. Relative `driverPath` values keep working.
- The process runs as UID 10001. It needs to read `connections.json` and the drivers, and to write
  the catalogs and logs under `/data`.
- `localhost` in a JDBC URL means the container itself. Use the database's host name, or
  `host.docker.internal` with Docker Desktop, or add `--network host` on Linux.
- A SQLite file has to be mounted too, at the path the URL names:
  `-v /srv/data/analytics.db:/dbs/analytics.db:ro` with `"url": "jdbc:sqlite:/dbs/analytics.db"`.

## Tuning a connection

| Field | Guidance |
|---|---|
| `queryTimeoutSeconds` | The server-side limit for one statement; a client's cancel does not interrupt a running query. Lower it (10–15 s) on production databases. A tool call may pass its own `timeoutSeconds`. |
| `maxRows` | Every returned row ends up in the agent's context. Lower it for small-context models and busy production databases; the agent can still pass a smaller `limit`. |
| `fetchSize` | Rows per network round trip. Leave it unless the result sets are wide. |
| `poolMaximumSize` | Tool calls on one MCP session run one at a time, so a single client rarely holds more than a few connections. `4` is a sensible cap for a production database; the default of `40` only matters when many sessions share one database. |
| `poolMinimumIdle` | `0` keeps the pool lazy: a configured but unused database never gets a connection. |
| `readonlyGuard` | Leave `strict`. Set `off` only for a connection whose database user is read-only, when the guard blocks a legitimate read (an unusual function call, vendor syntax the parser does not know). |
| `structureSnapshotSchemas` | The schemas `rebuildCatalog` captures. List every schema the agent works with, or only the default one is covered. |
| `usageNativeSchemas`, `usageNative*` | Which views, routines and triggers feed the usage catalog. On large schemas lower `usageNativeMaxObjects` or switch off routines. |
| `usageCatalogPaths` | Extra QueryUsage sources (directories, `.json`, `.zip`); see [usage-catalog-format.md](usage-catalog-format.md). |

## Checking a configuration

**1. Start the server by hand.** An MCP client that fails to start a server usually just says "failed
to connect". Running the jar in a terminal shows the actual error:

```bash
java -jar jdbc-mcp-server.jar < /dev/null
```

(On Windows PowerShell: `$null | java -jar jdbc-mcp-server.jar`.)

**2. Read the `Configured connections` line.** At startup the server logs every entry with its URL
(a `password=` URL parameter is masked) and, for entries it cannot use, the reason:

```text
Configured connections: orders@prod -> jdbc:postgresql://prod-db:5432/orders, shop-mysql -> jdbc:mysql://mysql.example.com:3306/shop (unusable: driverPath /home/me/.jdbc-mcp-server/drivers/mysql-connector-j-9.1.0.jar does not exist)
```

Logs go to stderr and to `<data-dir>/logs/jdbc-mcp-server.log`.

**3. Ask for `listConnections`.** It reads configuration only and opens no database connection:

```json
{
  "connections": [
    {"name": "orders@prod", "description": "Order service, PRODUCTION — keep queries small",
     "kind": "PostgreSQL", "defaultSchema": "public", "snapshotAvailable": true, "initialized": false},
    {"name": "shop-mysql", "kind": "Generic JDBC", "snapshotAvailable": false, "initialized": false,
     "configError": "driverPath /home/me/.jdbc-mcp-server/drivers/mysql-connector-j-9.1.0.jar does not exist"}
  ]
}
```

**4. Make one real call per connection**, for example `listSchemas`. Host, credentials, driver
loading, and a SQLite file's existence are checked only when a connection is first used.

## Changing a configuration

- **The file is read once, at startup.** After editing it, restart the MCP server: restart the
  client, or use its reconnect command (`/mcp` in Claude Code).
- **Adding** an entry costs nothing until it is used. **Removing** one leaves its
  `<data-dir>/<name>/` directory behind; delete it by hand if you no longer need it.
- **Renaming** a connection starts it with an empty catalog, because the catalog is keyed by name.
  To keep it, stop every server process and rename both the directory and the file inside it:
  `<old>/<old>.db` → `<new>/<new>.db`, plus any `<old>.db-wal` / `<old>.db-shm` next to it.
- **Pointing a name at a different database** keeps the old structure snapshot, which never expires.
  Run `rebuildCatalog`, or delete `<name>.db` while the server is stopped.
- **After upgrading the server**, run `rebuildCatalog` (or at least `invalidateUsageCatalogCache`) on
  connections whose catalogs were built by an older version, so that metadata fixes reach the
  persisted snapshot and usage index.

## Configuration errors

**The server does not start.** The file is present but unusable as a whole:

| Message | Cause |
|---|---|
| `Failed to read connections file …` | Invalid JSON: a trailing comma, a comment, an unquoted key, a single backslash in a Windows path |
| `Connections file … is empty` | The file contains `null` or nothing |
| `Invalid connection name '…'` | The name breaks `[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?`, is longer than 64 characters, or is `.` / `..` |
| `Connection '…' has no settings` | The entry is `null` |
| `Connection '…' has no 'url'` | `url` is missing or blank |
| `Environment variable 'X' referenced by connections.<name>.<field> … is not set` | A `${X}` placeholder whose variable is missing from the server's environment |
| `Unterminated ${...} placeholder` / `Empty ${} placeholder` | A malformed placeholder |

A missing file, or `"connections": {}`, is **not** an error: the server starts with no connections
and logs a warning.

**One connection is unusable** (`configError` in `listConnections`; the others keep working):

| Message | Cause |
|---|---|
| `Unsupported JDBC URL: '…' … set "driverPath"` | No built-in dialect recognizes the URL and there is no `driverPath` |
| `Unknown dialect '…'` | `dialect` is not one of `postgresql`, `oracle`, `mssql`, `firebird`, `sqlite`, `generic` |
| `driverPath … does not exist` | The jar or directory is missing; check the path relative to `connections.json` |

**The first call fails:**

| Message | Cause |
|---|---|
| `No driver in … accepts the URL` | The jars hold no driver for this URL prefix; fix the URL or set `driverClass` |
| `Cannot load driver class …` | `driverClass` is misspelled or not in the jars |
| `driverPath … contains no .jar files` | The directory is empty |
| `No bundled JDBC driver accepts …; set "driverPath"` | `"dialect": "generic"` on a URL without a bundled driver |
| Authentication or connection errors (`kind: "sql"`) | Wrong host, port, credentials, or network path; check with `psql`, `sqlplus` or `sqlcmd` |
| SQLite `unable to open database file` | The path does not exist or is not readable |
