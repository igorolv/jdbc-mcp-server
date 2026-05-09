# Usage Catalog Format

This project consumes a canonical **query usage record** format. It describes a SQL query,
its parameters, optional output semantics, and optional evidence of where the output is used.

The format is intentionally source-agnostic. Adapters for BI Publisher, DAO source code,
dashboard exports, stored report definitions, or any other external source are responsible for
converting their native data into this shape.

The server also derives records automatically from connected database metadata. Views, routines
and triggers are indexed with `source.kind` values such as `database-view`,
`database-function`, `database-procedure` and `database-trigger`; these records use the same
canonical shape as file-backed records. For routine and trigger bodies, an ANTLR-based
pre-extractor finds embedded DML/query statements before the existing JSqlParser analysis extracts
tables, columns and joins.

The JSON Schema is published at:

```text
src/main/resources/schemas/query-usage-record.schema.json
```

Examples:

```text
examples/usage/query-usage-record.minimal.json
examples/usage/query-usage-record.full.json
```

## Record Identity

Each record is stored under a stable UID derived from:

```text
{dataSource}/{source.path}#{source.unit}
```

When `source.unit` is absent or empty, the `#unit` suffix is omitted.

`source.path` is a stable source identifier, not necessarily a filesystem path. It can be a
repository path, URL, dashboard id, report id, or any adapter-defined identifier. It must be unique
enough within `dataSource` to support repeatable upserts.

Validation rules:

- `dataSource` is required and must not contain `/` or `#`.
- `source.kind` is required and is a free string, not an enum.
- `source.path` is required and must not contain `#`.
- `source.unit` is optional and must not contain `/` or `#` when present.
- `sql` is required.
- `schemaVersion` may be omitted; current version is `1`.

Re-ingesting the same UID replaces the previous child rows in one transaction.

## Minimal Record

```json
{
  "schemaVersion": 1,
  "dataSource": "SHOP",
  "source": {
    "kind": "manual",
    "path": "examples/manual/customer-count"
  },
  "sql": "SELECT COUNT(*) AS customer_count FROM customers"
}
```

This is enough for the server to parse and index tables, columns, parameters, and observed joins
when SQL parsing succeeds. SQL parse failures are still stored with `parseStatus = "failed"` so
source metadata is not lost.

## Optional Semantic Layer

Adapters may add:

- `businessLabel` - short human label for the query.
- `businessDomain` - grouping area or domain.
- `businessTags` - free-form search tags.
- `parameters` - parameter types/defaults and business descriptions.
- `outputs` - output aliases, expressions, labels, and derived physical columns.
- `fieldUsages` - where and how an output is consumed or rendered.
- `sourceMeta` - arbitrary adapter-provided provenance/audit JSON.

The server treats this semantic layer as best-effort evidence supplied by the adapter. It does not
require adapters to provide every field.

At runtime this evidence is kept separate from live database metadata:

- `declared_schema` comes from the connected database.
- `observed_query` comes from parser-derived table, column, parameter and join references in the
  cataloged SQL.
- `semantic_usage` comes from the optional business fields in this JSON format.

Schema-context tools may project `observed_query` and `semantic_usage` back onto physical tables
and columns as an `evidence` block. That projection is intentionally aggregative: the same table
can have several business roles, labels or domains with separate support counts and contributing
query UIDs.

Relationship edges in `schemaOverview` / `tableContext` / `findJoinPaths` carry a typed
three-layer `evidence` bundle bringing the same separation to FK / observed-join pairs:

- `declaredSchema` - present iff the edge originates from a declared catalog FK; carries the FK
  name and column lists.
- `observedQuery` - present iff the (table, column) pair appears as an equi-join in stored
  application queries; carries `joinSupport` and a capped uid preview.
- `semanticUsage` - terms shared across queries that touch *both* tables: business domains,
  business objects and output labels, plus the co-occurring query count and uid preview. This
  layer decorates existing edges only - it never proposes new relationships.

Equi-join pairs seen only in stored queries (no declared FK) are appended as new edges with
`relationshipType: "observed"` and `undirected: true`, between tables already in scope.

The same semantic fields are also search signals for `queryContext`: terms are matched against
business domains, tags, query labels, output labels and field-usage business objects, then mapped
back to the physical tables and columns derived from the SQL catalog.

## Free Classifiers

The following fields are intentionally open strings:

- `source.kind`
- `location.kind`
- `businessDomain`
- `businessTags[]`

The server may document examples, but it does not restrict these values. This keeps the format
usable for new source systems without changing the server.

Closed enums are used only where the server needs stable behavior:

- `fieldUsages[].transformation.kind`: `identity`, `aggregate`, `derived`, `conditional`,
  `filter`, `format`, `decode`, `other`
- `fieldUsages[].confidence`: `high`, `medium`, `low`

## Loading

The source of truth is a set of JSON files in this format. Configure the server with one or more
directories, individual JSON files, or zip archives; it scans them on startup and builds a runtime
usage index. Source-specific adapters should output canonical records; they do not need to be part
of this project.
