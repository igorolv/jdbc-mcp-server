package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogDisabledResponse;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

/**
 * MCP tools for the local usage catalog: a file-backed set of known SQL queries used by
 * applications and reports against the inspected database, indexed into a runtime H2 store
 * together with their business context.
 */
@Service
public class UsageTools {

    private static final Logger log = LoggerFactory.getLogger(UsageTools.class);

    private final UsageCatalogService service;
    private final JsonResponses json;
    private final ToolErrors errors;

    public UsageTools(UsageCatalogService service, JsonResponses json, ToolErrors errors) {
        this.service = service;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(description = "Return the runtime usage-catalog index status: configured JSON/zip sources, indexing state, record counts, parse failures, duplicate UIDs and load errors.")
    public String usageCatalogStatus() {
        log.info("Tool call: usageCatalogStatus");
        long start = System.nanoTime();
        String result = json.write(service.status());
        ToolLogger.completed(log, "usageCatalogStatus", start);
        return result;
    }

    @McpTool(description = "Invalidate the runtime usage-catalog index. The next usage-catalog lookup rebuilds it synchronously from configured files and database-native objects.")
    public String invalidateUsageCatalogCache() {
        log.info("Tool call: invalidateUsageCatalogCache");
        if (!service.enabled()) return disabled("invalidateUsageCatalogCache");
        long start = System.nanoTime();
        try {
            String result = json.write(service.invalidateIndex());
            ToolLogger.completed(log, "invalidateUsageCatalogCache", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "invalidateUsageCatalogCache", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "Return the full usage-catalog record for one query: header, parameters, parsed tables/columns/join pairs, outputs (with derived columns) and field usages.")
    public String getQuery(
            @McpToolParam(description = "Source kind (e.g. 'dao', 'report', 'database-view').") String sourceKind,
            @McpToolParam(description = "Source path — stable identifier, e.g. file path.") String sourcePath,
            @McpToolParam(description = "Source unit — sub-unit, e.g. method name (optional).", required = false) String sourceUnit
    ) {
        log.info("Tool call: getQuery (kind={}, path={}, unit={})", sourceKind, sourcePath, sourceUnit);
        if (!service.enabled()) return disabled("getQuery");
        long start = System.nanoTime();
        try {
            String result = json.write(service.getQuery(sourceKind, sourcePath, sourceUnit));
            ToolLogger.completed(log, "getQuery", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "getQuery", start, e.getMessage());
            return errors.argument(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "getQuery", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "List queries in the usage catalog. All filters are optional; default ordering is by most-recent ingest. sourcePath supports SQL LIKE wildcards ('%' / '_'). searchText performs a case-insensitive search across raw SQL, labels, source paths, and domains.")
    public String listQueries(
            @McpToolParam(description = "Filter by source path (LIKE — '%' / '_' allowed).", required = false) String sourcePath,
            @McpToolParam(description = "Filter by source kind ('bi-publisher-report', 'dao', etc.).", required = false) String sourceKind,
            @McpToolParam(description = "Filter by business domain (exact).", required = false) String businessDomain,
            @McpToolParam(description = "Filter by tag (exact).", required = false) String tag,
            @McpToolParam(description = "Filter by parse status: 'parsed' or 'failed'.", required = false) String parseStatus,
            @McpToolParam(description = "Full-text search across raw SQL, normalized SQL, business label, domain, and source path (case-insensitive).", required = false) String searchText,
            @McpToolParam(description = "Maximum rows to return (default 100, max 1000).", required = false) Integer limit,
            @McpToolParam(description = "Skip this many rows (for paging, default 0).", required = false) Integer offset
    ) {
        log.info("Tool call: listQueries (searchText={})", searchText);
        if (!service.enabled()) return disabledWithRows("listQueries", "queries");
        long start = System.nanoTime();
        try {
            String result = json.write(service.listQueries(
                    sourcePath, sourceKind, businessDomain, tag, parseStatus, searchText, limit, offset));
            ToolLogger.completed(log, "listQueries", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "listQueries", start, e.getMessage());
            return errors.argument(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listQueries", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "Find catalog queries that reference a given table. Matches case-insensitively on the resolved (uppercased, alias-expanded) table name. When schema is given, also matches queries where the table is referenced without an explicit schema.")
    public String findQueriesByTable(
            @McpToolParam(description = "Schema name (optional; case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Table name (required; case-insensitive).") String table
    ) {
        log.info("Tool call: findQueriesByTable (schema={}, table={})", schema, table);
        if (!service.enabled()) return disabledWithRows("findQueriesByTable", "matches");
        long start = System.nanoTime();
        try {
            String result = json.write(service.findQueriesByTable(schema, table));
            ToolLogger.completed(log, "findQueriesByTable", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "findQueriesByTable", start, e.getMessage());
            return errors.argument(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "findQueriesByTable", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "Find catalog queries that reference a given column. The 'context' field in each match indicates where the column is used: 'select' | 'where' | 'join' | 'order_by' | 'having'. Schema and table filters are optional and case-insensitive; when omitted, all columns with the given name are matched.")
    public String findQueriesByColumn(
            @McpToolParam(description = "Schema name (optional; case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Table name (optional; case-insensitive).", required = false) String table,
            @McpToolParam(description = "Column name (required; case-insensitive).") String column
    ) {
        log.info("Tool call: findQueriesByColumn (schema={}, table={}, column={})", schema, table, column);
        if (!service.enabled()) return disabledWithRows("findQueriesByColumn", "matches");
        long start = System.nanoTime();
        try {
            String result = json.write(service.findQueriesByColumn(schema, table, column));
            ToolLogger.completed(log, "findQueriesByColumn", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "findQueriesByColumn", start, e.getMessage());
            return errors.argument(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "findQueriesByColumn", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "Aggregate observed equi-join pairs across all stored queries. Each row is a (left_table.left_column = right_table.right_column) pair with its support count and the list of contributing query uids. Feeds the 'observedQuery' layer of the relationship 'evidence' bundle in tableContext / findJoinPaths. Non-equi joins (BETWEEN, function-based) are excluded.")
    public String observedRelationships(
            @McpToolParam(description = "Optional schema filter (case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Optional table filter — at least one side of the join must reference this table (case-insensitive).", required = false) String table,
            @McpToolParam(description = "Minimum number of distinct queries that must agree on the pair (default 1).", required = false) Integer minSupport
    ) {
        log.info("Tool call: observedRelationships (schema={}, table={})", schema, table);
        if (!service.enabled()) return disabledWithRows("observedRelationships", "relationships");
        long start = System.nanoTime();
        try {
            String result = json.write(service.observedRelationships(schema, table, minSupport == null ? 1 : minSupport));
            ToolLogger.completed(log, "observedRelationships", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "observedRelationships", start, e.getMessage());
            return errors.argument(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "observedRelationships", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "List business tags currently used in the catalog with their query counts. Helps the agent reuse an existing vocabulary instead of inventing new tags on each ingest.")
    public String listKnownTags() {
        log.info("Tool call: listKnownTags");
        if (!service.enabled()) return disabledWithRows("listKnownTags", "tags");
        long start = System.nanoTime();
        try {
            String result = json.write(service.listKnownTags());
            ToolLogger.completed(log, "listKnownTags", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listKnownTags", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "List business domains currently used in the catalog with their query counts.")
    public String listKnownDomains() {
        log.info("Tool call: listKnownDomains");
        if (!service.enabled()) return disabledWithRows("listKnownDomains", "domains");
        long start = System.nanoTime();
        try {
            String result = json.write(service.listKnownDomains());
            ToolLogger.completed(log, "listKnownDomains", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listKnownDomains", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "List source-kinds currently used in the catalog with their query counts. Helps the agent discover valid values for listQueries 'sourceKind' filter.")
    public String listKnownKinds() {
        log.info("Tool call: listKnownKinds");
        if (!service.enabled()) return disabledWithRows("listKnownKinds", "kinds");
        long start = System.nanoTime();
        try {
            String result = json.write(service.listKnownSourceKinds());
            ToolLogger.completed(log, "listKnownKinds", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listKnownKinds", start, e.getMessage());
            return errors.unexpected(e);
        }
    }

    // ---------------------------------------------------------------------------------------
    //  Disabled-catalog responses
    // ---------------------------------------------------------------------------------------

    private String disabled(String tool) {
        return json.write(UsageCatalogDisabledResponse.disabled(tool));
    }

    private String disabledWithRows(String tool, String collectionField) {
        return json.write(UsageCatalogDisabledResponse.withRows(tool, collectionField));
    }
}
