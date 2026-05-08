package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.usage.IngestPayload;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for the local usage catalog: a SQLite-backed store of known SQL queries used by
 * applications and reports against the inspected database, together with their business context.
 *
 * <p>Writes are local-only (the inspected JDBC database is never modified). The catalog is fed
 * by external callers — typically an AI agent that walks a source artifact (BI Publisher report
 * directory, DAO source, dashboard export) and synthesises rich payloads for ingest.
 */
@Service
public class UsageTools {

    private static final String INGEST_DESCRIPTION =
            "Ingest one query into the local usage catalog. The catalog stores queries used by "
            + "applications/reports against the inspected database, together with their business "
            + "context (parameters, output columns, where each output is rendered). The server "
            + "parses the SQL with JSqlParser and persists tables/columns/equi-join pairs as facts; "
            + "the caller adds the semantic layer.\n"
            + "\n"
            + "Identity: a uid is built from (dataSource, source.path, source.unit) as "
            + "'{dataSource}/{source.path}#{source.unit}' (the '#unit' suffix is omitted when "
            + "source.unit is empty). Re-ingest with the same uid replaces all child rows in one "
            + "transaction. dataSource and source.unit must not contain '/' or '#'; source.path "
            + "must not contain '#'. Parsing failures are tolerated — the row is stored with "
            + "parseStatus='failed' so business metadata is not lost.\n"
            + "\n"
            + "The shape of source/parameters/outputs/fieldUsages is described by the JSON schema "
            + "of each parameter (field names, types, enum values, descriptions). Only dataSource, "
            + "source (kind+path), and sql are required; everything else is optional.";

    private final UsageCatalogService service;
    private final MetadataService metadata;

    public UsageTools(UsageCatalogService service, MetadataService metadata) {
        this.service = service;
        this.metadata = metadata;
    }

    @McpTool(description = INGEST_DESCRIPTION)
    public String ingestQuery(
            @McpToolParam(description = "Logical database identifier scoping this query (e.g. 'SHOP'). Must not contain '/' or '#'.") String dataSource,
            @McpToolParam(description = "Where this query comes from: kind/path/unit.") IngestPayload.Source source,
            @McpToolParam(description = "SQL text. Named (':name') or positional ('?') bindings.") String sql,
            @McpToolParam(description = "Short business label of this query (for listings).", required = false) String businessLabel,
            @McpToolParam(description = "Business domain/area for grouping (free-text; see listKnownDomains).", required = false) String businessDomain,
            @McpToolParam(description = "Discovery tags (free-text; see listKnownTags).", required = false) List<String> businessTags,
            @McpToolParam(description = "Parameters with optional business descriptions.", required = false) List<IngestPayload.Param> parameters,
            @McpToolParam(description = "Output columns of the query with their business meaning and (optionally) the underlying physical columns they are derived from.", required = false) List<IngestPayload.Output> outputs,
            @McpToolParam(description = "Where in the consuming artifact each output is displayed (Excel cell, RTF region, dashboard widget, …) including transformation kind.", required = false) List<IngestPayload.FieldUsage> fieldUsages,
            @McpToolParam(description = "Arbitrary JSON blob preserved verbatim for audit (the original source artifact, etc.).", required = false) Map<String, Object> sourceMeta
    ) {
        if (!service.enabled()) return disabled("ingestQuery");
        try {
            IngestPayload.Request req = new IngestPayload.Request(
                    dataSource,
                    source,
                    businessLabel,
                    businessDomain,
                    businessTags,
                    sql,
                    parameters,
                    outputs,
                    fieldUsages,
                    sourceMeta
            );
            return JsonWriter.write(service.ingest(req));
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Delete usage-catalog records by source. Useful before a bulk re-ingest of a directory: pass dataSource alone to wipe everything for a logical DB, or narrow with sourcePath / sourceUnit. Returns the number of deleted query rows; child rows are removed via cascade.")
    public String deleteQueriesBySource(
            @McpToolParam(description = "Logical database identifier (required).") String dataSource,
            @McpToolParam(description = "Optional exact source path filter.", required = false) String sourcePath,
            @McpToolParam(description = "Optional exact source unit filter (use empty string '' to match records without a unit).", required = false) String sourceUnit
    ) {
        if (!service.enabled()) return disabled("deleteQueriesBySource");
        try {
            return JsonWriter.write(service.deleteBySource(dataSource, sourcePath, sourceUnit));
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Return the full usage-catalog record for one query: header, parameters, parsed tables/columns/join pairs, outputs (with derived columns) and field usages. Pass the uid returned by ingestQuery, or compose it as '{dataSource}/{path}#{unit}'.")
    public String getQuery(
            @McpToolParam(description = "Query uid. Format: '{dataSource}/{source.path}#{source.unit}' (without '#unit' when there is no unit).") String uid
    ) {
        if (!service.enabled()) return disabled("getQuery");
        try {
            return JsonWriter.write(service.getQuery(uid));
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "List queries in the usage catalog. All filters are optional; default ordering is by most-recent ingest. sourcePath supports SQL LIKE wildcards ('%' / '_').")
    public String listQueries(
            @McpToolParam(description = "Filter by logical database identifier.", required = false) String dataSource,
            @McpToolParam(description = "Filter by source path (LIKE — '%' / '_' allowed).", required = false) String sourcePath,
            @McpToolParam(description = "Filter by source kind ('bi-publisher-report', 'dao', etc.).", required = false) String sourceKind,
            @McpToolParam(description = "Filter by business domain (exact).", required = false) String businessDomain,
            @McpToolParam(description = "Filter by tag (exact).", required = false) String tag,
            @McpToolParam(description = "Filter by parse status: 'parsed' or 'failed'.", required = false) String parseStatus,
            @McpToolParam(description = "Maximum rows to return (default 100, max 1000).", required = false) Integer limit,
            @McpToolParam(description = "Skip this many rows (for paging, default 0).", required = false) Integer offset
    ) {
        if (!service.enabled()) return disabledWithRows("listQueries", "queries");
        try {
            return JsonWriter.write(service.listQueries(
                    dataSource, sourcePath, sourceKind, businessDomain, tag, parseStatus, limit, offset));
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Find catalog queries that reference a given table. Matches case-insensitively on the resolved (uppercased, alias-expanded) table name. When schema is given, also matches queries where the table is referenced without an explicit schema.")
    public String findQueriesByTable(
            @McpToolParam(description = "Schema name (optional; case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Table name (required; case-insensitive).") String table
    ) {
        if (!service.enabled()) return disabledWithRows("findQueriesByTable", "matches");
        try {
            return JsonWriter.write(service.findQueriesByTable(schema, table));
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Find catalog queries that reference a given column. The 'context' field in each match indicates where the column is used: 'select' | 'where' | 'join' | 'order_by' | 'having'. Schema and table filters are optional and case-insensitive; when omitted, all columns with the given name are matched.")
    public String findQueriesByColumn(
            @McpToolParam(description = "Schema name (optional; case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Table name (optional; case-insensitive).", required = false) String table,
            @McpToolParam(description = "Column name (required; case-insensitive).") String column
    ) {
        if (!service.enabled()) return disabledWithRows("findQueriesByColumn", "matches");
        try {
            return JsonWriter.write(service.findQueriesByColumn(schema, table, column));
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Aggregate observed equi-join pairs across all stored queries. Each row is a (left_table.left_column = right_table.right_column) pair with its support count and the list of contributing query uids. Feeds the evidenceLevel decoration in schemaOverview / tableContext / findJoinPaths. Non-equi joins (BETWEEN, function-based) are excluded.")
    public String observedRelationships(
            @McpToolParam(description = "Optional schema filter (case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Optional table filter — at least one side of the join must reference this table (case-insensitive).", required = false) String table,
            @McpToolParam(description = "Minimum number of distinct queries that must agree on the pair (default 1).", required = false) Integer minSupport
    ) {
        if (!service.enabled()) return disabledWithRows("observedRelationships", "relationships");
        try {
            return JsonWriter.write(service.observedRelationships(schema, table, minSupport == null ? 1 : minSupport));
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Re-resolve unqualified table references in stored queries against the live JDBC schema. "
            + "For every distinct unresolved raw table name in the catalog (scoped to the given dataSource), looks up "
            + "the name across all non-system schemas in the inspected database. If exactly one match is found the "
            + "schema is filled in (and propagated to query_column / query_join rows that referenced it); multiple "
            + "matches mark the row 'ambiguous'; zero matches leave it 'unresolved'. Already-resolved or CTE rows are "
            + "untouched. Requires a working JDBC connection to the inspected database.")
    public String reresolveQueries(
            @McpToolParam(description = "Logical database identifier (matches the dataSource used during ingest). When omitted, all queries in the catalog are re-resolved.", required = false) String dataSource
    ) {
        if (!service.enabled()) return disabled("reresolveQueries");
        try {
            return JsonWriter.write(service.reresolve(dataSource, name -> {
                List<Map<String, Object>> matches = metadata.findTablesByName(name);
                List<String[]> out = new ArrayList<>(matches.size());
                for (Map<String, Object> row : matches) {
                    Object schema = row.get("schema");
                    Object table = row.get("name");
                    out.add(new String[]{
                            schema == null ? null : schema.toString(),
                            table == null ? null : table.toString()
                    });
                }
                return out;
            }));
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "List business tags currently used in the catalog with their query counts. Helps the agent reuse an existing vocabulary instead of inventing new tags on each ingest.")
    public String listKnownTags(
            @McpToolParam(description = "Optional dataSource filter.", required = false) String dataSource
    ) {
        if (!service.enabled()) return disabledWithRows("listKnownTags", "tags");
        try {
            return JsonWriter.write(service.listKnownTags(dataSource));
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "List business domains currently used in the catalog with their query counts.")
    public String listKnownDomains(
            @McpToolParam(description = "Optional dataSource filter.", required = false) String dataSource
    ) {
        if (!service.enabled()) return disabledWithRows("listKnownDomains", "domains");
        try {
            return JsonWriter.write(service.listKnownDomains(dataSource));
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    // ---------------------------------------------------------------------------------------
    //  Disabled-catalog responses
    // ---------------------------------------------------------------------------------------

    private static String disabled(String tool) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "usage catalog is disabled (set JDBC_USAGE_CATALOG_ENABLED=true to enable)");
        body.put("kind", "disabled");
        body.put("tool", tool);
        return JsonWriter.write(body);
    }

    private static String disabledWithRows(String tool, String collectionField) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalog_enabled", false);
        body.put("tool", tool);
        body.put(collectionField, List.of());
        body.put("count", 0);
        return JsonWriter.write(body);
    }
}
