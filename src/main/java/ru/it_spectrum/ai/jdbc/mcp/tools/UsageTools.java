package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByColumnResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByTableResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownDomainsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownSourceKindsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownTagsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ListQueriesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ObservedRelationshipsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QueryDetail;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

/**
 * MCP tools for the local usage catalog: a file-backed set of known SQL queries used by
 * applications and reports against the inspected database, indexed into a persistent SQLite store
 * together with their business context.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "usage", havingValue = "true", matchIfMissing = true)
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

    @McpTool(
            description = "Return the runtime usage-catalog index status: configured JSON/zip sources, indexing state, record counts, parse failures, duplicate UIDs and load errors.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public UsageCatalogStatus usageCatalogStatus() {
        log.info("Tool call: usageCatalogStatus");
        long start = System.nanoTime();
        UsageCatalogStatus result = service.status();
        ToolLogger.completed(log, "usageCatalogStatus", start);
        return result;
    }

    @McpTool(
            description = "Invalidate the runtime usage-catalog index. The next usage-catalog lookup rebuilds it synchronously from configured files and database-native objects.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public UsageCatalogStatus invalidateUsageCatalogCache() {
        log.info("Tool call: invalidateUsageCatalogCache");
        if (!service.enabled()) throw disabledException("invalidateUsageCatalogCache");
        long start = System.nanoTime();
        try {
            UsageCatalogStatus result = service.invalidateIndex();
            ToolLogger.completed(log, "invalidateUsageCatalogCache", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "invalidateUsageCatalogCache", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "Return the full usage-catalog record for one query: header, parameters, parsed tables/columns/join pairs, outputs (with derived columns) and field usages.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public QueryDetail getQuery(
            @McpToolParam(description = "Source kind (e.g. 'dao', 'report', 'database-view').") String sourceKind,
            @McpToolParam(description = "Source path — stable identifier, e.g. file path.") String sourcePath,
            @McpToolParam(description = "Source unit — sub-unit, e.g. method name.", required = false) String sourceUnit
    ) {
        log.info("Tool call: getQuery (kind={}, path={}, unit={})", sourceKind, sourcePath, sourceUnit);
        if (!service.enabled()) throw disabledException("getQuery");
        long start = System.nanoTime();
        try {
            QueryDetail result = service.getQuery(sourceKind, sourcePath, sourceUnit);
            ToolLogger.completed(log, "getQuery", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "getQuery", start, e.getMessage());
            throw errors.argumentException(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "getQuery", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "List queries in the usage catalog. All filters are optional; default ordering is by most-recent ingest. sourcePath supports SQL LIKE wildcards ('%' / '_'). searchText performs a case-insensitive search across raw SQL, labels, source paths, and domains.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListQueriesResult listQueries(
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
        if (!service.enabled()) throw disabledException("listQueries");
        long start = System.nanoTime();
        try {
            ListQueriesResult result = service.listQueries(
                    sourcePath, sourceKind, businessDomain, tag, parseStatus, searchText, limit, offset);
            ToolLogger.completed(log, "listQueries", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "listQueries", start, e.getMessage());
            throw errors.argumentException(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listQueries", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "Find catalog queries that reference a given table. Matches case-insensitively on the resolved (uppercased, alias-expanded) table name. When schema is given, also matches queries where the table is referenced without an explicit schema.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FindQueriesByTableResult findQueriesByTable(
            @McpToolParam(description = "Schema (case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Table name (required; case-insensitive).") String table
    ) {
        log.info("Tool call: findQueriesByTable (schema={}, table={})", schema, table);
        if (!service.enabled()) throw disabledException("findQueriesByTable");
        long start = System.nanoTime();
        try {
            FindQueriesByTableResult result = service.findQueriesByTable(schema, table);
            ToolLogger.completed(log, "findQueriesByTable", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "findQueriesByTable", start, e.getMessage());
            throw errors.argumentException(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "findQueriesByTable", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "Find catalog queries that reference a given column. The 'context' field in each match indicates where the column is used: 'select' | 'where' | 'join' | 'order_by' | 'having'. Schema and table filters are optional and case-insensitive; when omitted, all columns with the given name are matched.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FindQueriesByColumnResult findQueriesByColumn(
            @McpToolParam(description = "Schema (case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Table name (case-insensitive).", required = false) String table,
            @McpToolParam(description = "Column name (required; case-insensitive).") String column
    ) {
        log.info("Tool call: findQueriesByColumn (schema={}, table={}, column={})", schema, table, column);
        if (!service.enabled()) throw disabledException("findQueriesByColumn");
        long start = System.nanoTime();
        try {
            FindQueriesByColumnResult result = service.findQueriesByColumn(schema, table, column);
            ToolLogger.completed(log, "findQueriesByColumn", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "findQueriesByColumn", start, e.getMessage());
            throw errors.argumentException(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "findQueriesByColumn", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "Aggregate observed equi-join pairs across all stored queries. Each row is a (left_table.left_column = right_table.right_column) pair with its support count and the list of contributing query uids. Non-equi joins (BETWEEN, function-based) are excluded.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ObservedRelationshipsResult observedRelationships(
            @McpToolParam(description = "Optional schema filter (case-insensitive).", required = false) String schema,
            @McpToolParam(description = "Optional table filter — at least one side of the join must reference this table (case-insensitive).", required = false) String table,
            @McpToolParam(description = "Minimum number of distinct queries that must agree on the pair (default 1).", required = false) Integer minSupport
    ) {
        log.info("Tool call: observedRelationships (schema={}, table={})", schema, table);
        if (!service.enabled()) throw disabledException("observedRelationships");
        long start = System.nanoTime();
        try {
            ObservedRelationshipsResult result = service.observedRelationships(schema, table, minSupport == null ? 1 : minSupport);
            ToolLogger.completed(log, "observedRelationships", start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "observedRelationships", start, e.getMessage());
            throw errors.argumentException(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "observedRelationships", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "List business tags currently used in the catalog with their query counts. Helps the agent reuse an existing vocabulary instead of inventing new tags on each ingest.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public KnownTagsResult listKnownTags() {
        log.info("Tool call: listKnownTags");
        if (!service.enabled()) throw disabledException("listKnownTags");
        long start = System.nanoTime();
        try {
            KnownTagsResult result = service.listKnownTags();
            ToolLogger.completed(log, "listKnownTags", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listKnownTags", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "List business domains currently used in the catalog with their query counts.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public KnownDomainsResult listKnownDomains() {
        log.info("Tool call: listKnownDomains");
        if (!service.enabled()) throw disabledException("listKnownDomains");
        long start = System.nanoTime();
        try {
            KnownDomainsResult result = service.listKnownDomains();
            ToolLogger.completed(log, "listKnownDomains", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listKnownDomains", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "List source-kinds currently used in the catalog with their query counts. Helps the agent discover valid values for listQueries 'sourceKind' filter.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public KnownSourceKindsResult listKnownKinds() {
        log.info("Tool call: listKnownKinds");
        if (!service.enabled()) throw disabledException("listKnownKinds");
        long start = System.nanoTime();
        try {
            KnownSourceKindsResult result = service.listKnownSourceKinds();
            ToolLogger.completed(log, "listKnownKinds", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listKnownKinds", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    // ---------------------------------------------------------------------------------------
    //  Disabled-catalog responses
    // ---------------------------------------------------------------------------------------

    private RuntimeException disabledException(String tool) {
        return errors.argumentException(tool + ": usage catalog is disabled (set JDBC_USAGE_CATALOG_ENABLED=true to enable)");
    }
}
