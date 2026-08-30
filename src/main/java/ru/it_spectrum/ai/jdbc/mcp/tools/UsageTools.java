package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByColumnResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByTableResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownDomainsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownSourceKindsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownTagsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ListQueriesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ObservedRelationshipsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QueryDetail;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;

/**
 * MCP tools for the local usage catalog: a file-backed set of known SQL queries used by
 * applications and reports against the inspected database, indexed into a persistent SQLite store
 * together with their business context.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "usage", havingValue = "true", matchIfMissing = true)
public class UsageTools {

    private static final Logger log = LoggerFactory.getLogger(UsageTools.class);

    private final ConnectionRegistry connections;
    private final JsonResponses json;
    private final ToolErrors errors;

    public UsageTools(ConnectionRegistry connections, JsonResponses json, ToolErrors errors) {
        this.connections = connections;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Return usage-catalog sources, indexing state, record/parse/duplicate counts and load errors.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public UsageCatalogStatus usageCatalogStatus(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: usageCatalogStatus");
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        UsageCatalogStatus result = ctx.usageCatalog().status().withConnection(ctx.name());
        ToolLogger.completed(log, "usageCatalogStatus", start);
        return result;
    }

    @McpTool(
            description = "Invalidate the runtime usage index; the next lookup rebuilds it synchronously from " +
            "configured files and database objects.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public UsageCatalogStatus invalidateUsageCatalogCache(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: invalidateUsageCatalogCache");
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("invalidateUsageCatalogCache");
        long start = System.nanoTime();
        try {
            UsageCatalogStatus result = ctx.usageCatalog().invalidateIndex().withConnection(ctx.name());
            ToolLogger.completed(log, "invalidateUsageCatalogCache", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "invalidateUsageCatalogCache", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "Return one full usage-catalog query record with SQL, parameters, parsed " +
            "tables/columns/joins, derived outputs and field usages.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public QueryDetail getQuery(
            @McpToolParam(description = "Source kind, e.g. dao, report, database-view.") String sourceKind,
            @McpToolParam(description = "Stable source path, e.g. file path.") String sourcePath,
            @McpToolParam(description = "Optional sub-unit, e.g. method name.", required = false) String sourceUnit,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: getQuery (kind={}, path={}, unit={})", sourceKind, sourcePath, sourceUnit);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("getQuery");
        long start = System.nanoTime();
        try {
            QueryDetail result = ctx.usageCatalog().getQuery(sourceKind, sourcePath, sourceUnit);
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
            description = "List usage-catalog queries, newest ingest first; filters combine.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListQueriesResult listQueries(
            @McpToolParam(description = "Source path LIKE pattern ('%'/'_').", required = false) String sourcePath,
            @McpToolParam(description = "Source kind, e.g. bi-publisher-report or dao.", required = false) String sourceKind,
            @McpToolParam(description = "Exact business domain.", required = false) String businessDomain,
            @McpToolParam(description = "Exact tag.", required = false) String tag,
            @McpToolParam(description = "Parse status: parsed or failed.", required = false) String parseStatus,
            @McpToolParam(description = "Case-insensitive full-text search over SQL, labels, domains and source paths.", required = false) String searchText,
            @McpToolParam(description = "Rows to return (default 100, max 1000).", required = false) Integer limit,
            @McpToolParam(description = "Paging offset (default 0).", required = false) Integer offset,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: listQueries (searchText={})", searchText);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("listQueries");
        long start = System.nanoTime();
        try {
            ListQueriesResult result = ctx.usageCatalog().listQueries(
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
            description = "Find catalog queries referencing a table, matching resolved names case-insensitively; " +
            "a schema filter also includes unqualified references.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FindQueriesByTableResult findQueriesByTable(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: findQueriesByTable (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("findQueriesByTable");
        long start = System.nanoTime();
        try {
            FindQueriesByTableResult result = ctx.usageCatalog().findQueriesByTable(schema, table);
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
            description = "Find catalog queries referencing a column; each match identifies " +
            "select/where/join/order_by/having context. Filters are case-insensitive; omit schema/table to " +
            "match all tables.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FindQueriesByColumnResult findQueriesByColumn(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "", required = false) String table,
            @McpToolParam(description = "") String column,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: findQueriesByColumn (schema={}, table={}, column={})", schema, table, column);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("findQueriesByColumn");
        long start = System.nanoTime();
        try {
            FindQueriesByColumnResult result = ctx.usageCatalog().findQueriesByColumn(schema, table, column);
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
            description = "Aggregate observed equi-join column pairs with support counts and query UIDs; excludes " +
            "non-equi joins.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ObservedRelationshipsResult observedRelationships(
            @McpToolParam(description = "Case-insensitive filter.", required = false) String schema,
            @McpToolParam(description = "Require either join side to use this table.", required = false) String table,
            @McpToolParam(description = "Minimum supporting queries (default 1).", required = false) Integer minSupport,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: observedRelationships (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("observedRelationships");
        long start = System.nanoTime();
        try {
            ObservedRelationshipsResult result = ctx.usageCatalog().observedRelationships(schema, table, minSupport == null ? 1 : minSupport);
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
            description = "List business tags and query counts.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public KnownTagsResult listKnownTags(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: listKnownTags");
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("listKnownTags");
        long start = System.nanoTime();
        try {
            KnownTagsResult result = ctx.usageCatalog().listKnownTags();
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
    public KnownDomainsResult listKnownDomains(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: listKnownDomains");
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("listKnownDomains");
        long start = System.nanoTime();
        try {
            KnownDomainsResult result = ctx.usageCatalog().listKnownDomains();
            ToolLogger.completed(log, "listKnownDomains", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listKnownDomains", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    @McpTool(
            description = "List source kinds and query counts.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public KnownSourceKindsResult listKnownKinds(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: listKnownKinds");
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        if (!ctx.usageCatalog().enabled()) throw disabledException("listKnownKinds");
        long start = System.nanoTime();
        try {
            KnownSourceKindsResult result = ctx.usageCatalog().listKnownSourceKinds();
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
