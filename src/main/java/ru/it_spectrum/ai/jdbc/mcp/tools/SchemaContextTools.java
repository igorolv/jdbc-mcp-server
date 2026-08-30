package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.model.context.FindJoinPaths;
import ru.it_spectrum.ai.jdbc.mcp.model.context.QueryContext;
import ru.it_spectrum.ai.jdbc.mcp.model.context.SchemaGraph;
import ru.it_spectrum.ai.jdbc.mcp.model.context.SchemaLint;
import ru.it_spectrum.ai.jdbc.mcp.model.context.TableContext;

import java.sql.SQLException;

/**
 * Higher-level schema context tools for SQL-writing agents.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "schema-context", havingValue = "true", matchIfMissing = true)
public class SchemaContextTools {

    private static final Logger log = LoggerFactory.getLogger(SchemaContextTools.class);

    private final ConnectionRegistry connections;
    private final JsonResponses json;
    private final ToolErrors errors;

    public SchemaContextTools(ConnectionRegistry connections, JsonResponses json, ToolErrors errors) {
        this.connections = connections;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Return a known table, nearby FK parent/child tables and relationship edges for queries " +
            "that need nearby joins.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public TableContext tableContext(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "FK depth (default 1).", required = false) Integer depth,
            @McpToolParam(description = "Include child references (default true).", required = false) Boolean includeIncoming,
            @McpToolParam(description = "Include row/size/activity stats (default false).", required = false) Boolean includeStats,
            @McpToolParam(description = "Include declared/observed/semantic usage evidence (default: catalog enabled).", required = false) Boolean includeObserved,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: tableContext (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var result = ctx.schemaContext().tableContext(
                    schema, table, depth, includeIncoming, includeStats, includeObserved);
            ToolLogger.completed(log, "tableContext", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "tableContext", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "tableContext", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Return FK join paths between two tables, traversing both directions; each edge includes " +
            "its join condition.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FindJoinPaths findJoinPaths(
            @McpToolParam(description = "", required = false) String fromSchema,
            @McpToolParam(description = "") String fromTable,
            @McpToolParam(description = "", required = false) String toSchema,
            @McpToolParam(description = "") String toTable,
            @McpToolParam(description = "Maximum FK hops. Default 4.", required = false) Integer maxDepth,
            @McpToolParam(description = "Maximum paths to return. Default 5, max 25.", required = false) Integer maxPaths,
            @McpToolParam(description = "Maximum tables to scan (default 300).", required = false) Integer scanLimit,
            @McpToolParam(description = "Include usage-catalog equi-joins (default: catalog enabled).", required = false) Boolean includeObserved,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: findJoinPaths ({}.{} -> {}.{})", fromSchema, fromTable, toSchema, toTable);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var result = ctx.schemaContext().findJoinPaths(
                    fromSchema, fromTable, toSchema, toTable, maxDepth, maxPaths, scanLimit,
                    includeObserved);
            ToolLogger.completed(log, "findJoinPaths", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "findJoinPaths", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "findJoinPaths", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Audit schema risks: missing PKs, unindexed/mismatched FKs, nullable unique columns, " +
            "status/type columns without CHECK, orphan *_id columns, missing remarks, isolation and wide tables.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SchemaLint schemaLint(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "Omit to scan the schema.", required = false) String table,
            @McpToolParam(description = "Checks CSV, e.g. missingPrimaryKey,fkWithoutIndex; omit for defaults.", required = false) String checks,
            @McpToolParam(description = "Tables to scan (default 50).", required = false) Integer maxTables,
            @McpToolParam(description = "Findings to return (default 200).", required = false) Integer maxFindings,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: schemaLint (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var result = ctx.schemaContext().schemaLint(
                    schema, table, checks, maxTables, maxFindings);
            ToolLogger.completed(log, "schemaLint", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "schemaLint", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "schemaLint", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Return a plain-text schema map with table/view column counts, PKs, relationship counts, " +
            "key-like columns, central/isolated tables and key FK relationships. Use for broad table discovery.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String schemaBrief(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "Terms to narrow discovery; falls back to full schema on no match.", required = false) String terms,
            @McpToolParam(description = "Tables/views to include (default 2000).", required = false) Integer maxTables,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: schemaBrief (schema={})", schema);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            String result = ctx.schemaContext().schemaBrief(schema, terms, maxTables);
            ToolLogger.completed(log, "schemaBrief", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "schemaBrief", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "schemaBrief", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Return schema graph nodes/edges, central/isolated tables, connected components, cycle " +
            "hints and an optional shortest path.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SchemaGraph schemaGraph(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "Tables to scan (default 50).", required = false) Integer maxTables,
            @McpToolParam(description = "Optional shortest-path start.", required = false) String fromTable,
            @McpToolParam(description = "Optional shortest-path target.", required = false) String toTable,
            @McpToolParam(description = "Shortest-path hops (default 4).", required = false) Integer maxDepth,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: schemaGraph (schema={})", schema);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var result = ctx.schemaContext().schemaGraph(
                    schema, maxTables, fromTable, toTable, maxDepth);
            ToolLogger.completed(log, "schemaGraph", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "schemaGraph", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "schemaGraph", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Build SQL-authoring context from terms and/or tables: relevant columns, constraints, " +
            "allowed values, relationships, join paths and optional samples.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public QueryContext queryContext(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "User terms, e.g. 'customers order totals'.", required = false) String terms,
            @McpToolParam(description = "Force-include tables (CSV), e.g. customers,orders.", required = false) String tables,
            @McpToolParam(description = "Include up to 3 rows per table (default false).", required = false) Boolean includeSamples,
            @McpToolParam(description = "Tables to include (default 12).", required = false) Integer maxTables,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: queryContext (schema={})", schema);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var result = ctx.schemaContext().queryContext(
                    schema, terms, tables, includeSamples, maxTables);
            ToolLogger.completed(log, "queryContext", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "queryContext", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "queryContext", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Return a DOT/Graphviz ERD with table columns/types, PK/FK markers and join-condition edges.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String schemaGraphDot(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "Tables to include (CSV); omit for all.", required = false) String tables,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: schemaGraphDot (schema={})", schema);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            String result = ctx.schemaContext().schemaGraphDot(schema, tables);
            ToolLogger.completed(log, "schemaGraphDot", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "schemaGraphDot", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "schemaGraphDot", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }
}
