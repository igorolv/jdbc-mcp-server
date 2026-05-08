package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogIndexer;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for the local usage catalog: a file-backed set of known SQL queries used by
 * applications and reports against the inspected database, indexed into a runtime H2 store
 * together with their business context.
 */
@Service
public class UsageTools {

    private final UsageCatalogService service;
    private final MetadataService metadata;
    private final UsageCatalogIndexer indexer;

    public UsageTools(UsageCatalogService service, MetadataService metadata, UsageCatalogIndexer indexer) {
        this.service = service;
        this.metadata = metadata;
        this.indexer = indexer;
    }

    @McpTool(description = "Return the runtime usage-catalog index status: configured JSON/zip sources, indexing state, record counts, parse failures, duplicate UIDs and load errors.")
    public String usageCatalogStatus() {
        return JsonWriter.write(indexer.status());
    }

    @McpTool(description = "Refresh the runtime usage-catalog index from the configured directories, JSON files, and zip archives. When background indexing is enabled this starts a rebuild and returns the current status immediately.")
    public String refreshUsageCatalog() {
        if (!service.enabled()) return disabled("refreshUsageCatalog");
        try {
            return JsonWriter.write(indexer.refresh());
        } catch (RuntimeException e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Return the full usage-catalog record for one query: header, parameters, parsed tables/columns/join pairs, outputs (with derived columns) and field usages. Compose uid as '{dataSource}/{path}#{unit}' (without '#unit' when there is no unit).")
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

    @McpTool(description = "Aggregate observed equi-join pairs across all stored queries. Each row is a (left_table.left_column = right_table.right_column) pair with its support count and the list of contributing query uids. Feeds the 'observedQuery' layer of the relationship 'evidence' bundle in schemaOverview / tableContext / findJoinPaths. Non-equi joins (BETWEEN, function-based) are excluded.")
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
                List<TableEntry> matches = metadata.findTablesByName(name);
                List<String[]> out = new ArrayList<>(matches.size());
                for (TableEntry row : matches) {
                    out.add(new String[]{row.schema(), row.name()});
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
