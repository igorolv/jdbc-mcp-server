# Usage Catalog Format

This project consumes a canonical **query usage record** format. It describes a SQL query,
its parameters, optional output semantics, and optional evidence of where the output is used.

The format is intentionally source-agnostic. Adapters for BI Publisher, DAO source code,
dashboard exports, stored report definitions, or any other source are responsible for converting
their native data into this shape. The JDBC MCP server does not parse those native formats.

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

The MCP `ingestQuery` tool accepts the same logical shape for a single record, but MCP is only one
transport. Bulk loading should deserialize canonical records and pass them to the same internal
ingestion service. Source-specific adapters should output canonical records; they do not need to
be part of this project.
