package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;
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

    private final SchemaContextService schemaContext;
    private final JsonResponses json;
    private final ToolErrors errors;

    public SchemaContextTools(SchemaContextService schemaContext, JsonResponses json, ToolErrors errors) {
        this.schemaContext = schemaContext;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Return a compact context around one table: the table itself, FK parent tables, " +
            "optionally child tables that reference it, and relationship edges. " +
            "Use this when a query starts from a known table and needs nearby joins. " +
            "Depth defaults to 1 and is capped at 4.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public TableContext tableContext(
            @McpToolParam(description = "Schema (optional; default schema)", required = false) String schema,
            @McpToolParam(description = "Root table or view name") String table,
            @McpToolParam(description = "FK traversal depth. Default 1, capped at 4.", required = false) Integer depth,
            @McpToolParam(description = "Include incoming references from child tables. Default true.", required = false) Boolean includeIncoming,
            @McpToolParam(description = "Include per-table row/size/activity stats where available. Default false.", required = false) Boolean includeStats,
            @McpToolParam(description = "Augment relationships with usage-catalog evidence (declared FKs, observed joins, semantic usage). Default: true if the catalog is enabled, else false.", required = false) Boolean includeObserved
    ) {
        log.info("Tool call: tableContext (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        try {
            var result = schemaContext.tableContext(
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
            description = "Find FK-based join paths between two tables. " +
            "The graph is traversed in both FK directions and each edge includes a joinCondition. " +
            "Use this when you know the start and target tables but not the intermediate joins. " +
            "maxDepth defaults to 4 and is capped at 4; maxPaths defaults to 5.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FindJoinPaths findJoinPaths(
            @McpToolParam(description = "Start schema (optional — defaults to current/default schema)", required = false) String fromSchema,
            @McpToolParam(description = "Start table name") String fromTable,
            @McpToolParam(description = "Target schema (optional — defaults to current/default schema)", required = false) String toSchema,
            @McpToolParam(description = "Target table name") String toTable,
            @McpToolParam(description = "Maximum FK hops. Default 4, capped at 4.", required = false) Integer maxDepth,
            @McpToolParam(description = "Maximum paths to return. Default 5, capped at 25.", required = false) Integer maxPaths,
            @McpToolParam(description = "Maximum schema tables to scan when building the graph. Default 300, capped at 300.", required = false) Integer scanLimit,
            @McpToolParam(description = "Also use observed equi-joins from the usage catalog as extra graph edges. Default: true if the catalog is enabled, else false.", required = false) Boolean includeObserved
    ) {
        log.info("Tool call: findJoinPaths ({}.{} -> {}.{})", fromSchema, fromTable, toSchema, toTable);
        long start = System.nanoTime();
        try {
            var result = schemaContext.findJoinPaths(
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
            description = "Run a compact schema lint audit for SQL-writing and query-optimization risks. " +
            "Checks include missing primary keys, FK columns without supporting indexes, FK type mismatch, " +
            "nullable unique columns, status/type columns without CHECK, " +
            "orphan *_id columns, missing remarks, isolated tables, and wide tables. " +
            "The optional checks parameter is a comma-separated allow-list; omit it to run the default set.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SchemaLint schemaLint(
            @McpToolParam(description = "Schema (optional; default schema)", required = false) String schema,
            @McpToolParam(description = "Single table to lint (optional — omit to scan the schema)", required = false) String table,
            @McpToolParam(description = "Comma-separated checks to run, e.g. 'missingPrimaryKey,fkWithoutIndex' (optional)", required = false) String checks,
            @McpToolParam(description = "Maximum schema tables to scan. Default 50, capped at 300.", required = false) Integer maxTables,
            @McpToolParam(description = "Maximum findings to return. Default 200, capped at 1000.", required = false) Integer maxFindings
    ) {
        log.info("Tool call: schemaLint (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        try {
            var result = schemaContext.schemaLint(
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
            description = "Return a plain-text full-schema map for SQL authoring. " +
            "Lists all matching tables/views with column counts, PK, relationship counts, and key-like columns; " +
            "also summarizes central/isolated tables and key FK relationships. " +
            "Use this first when the relevant tables are not known yet, then call queryContext for detailed context.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String schemaBrief(
            @McpToolParam(description = "Schema (optional; default schema)", required = false) String schema,
            @McpToolParam(description = "Optional search terms to narrow table discovery; falls back to whole schema if no table matches.", required = false) String terms,
            @McpToolParam(description = "Maximum tables/views to include as a safety cap. Default 2000, capped at 5000.", required = false) Integer maxTables
    ) {
        log.info("Tool call: schemaBrief (schema={})", schema);
        long start = System.nanoTime();
        try {
            String result = schemaContext.schemaBrief(schema, terms, maxTables);
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
            description = "Return relationship graph metrics for a schema: nodes with incoming/outgoing degree " +
            "and classification, edges, central tables, isolated tables, connected components, cycle hints, " +
            "and optionally the shortest path between two tables.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SchemaGraph schemaGraph(
            @McpToolParam(description = "Schema (optional; default schema)", required = false) String schema,
            @McpToolParam(description = "Maximum tables to scan. Default 50, capped at 300.", required = false) Integer maxTables,
            @McpToolParam(description = "Optional start table for shortestPath.", required = false) String fromTable,
            @McpToolParam(description = "Optional target table for shortestPath.", required = false) String toTable,
            @McpToolParam(description = "Maximum hops for shortestPath. Default 4, capped at 4.", required = false) Integer maxDepth
    ) {
        log.info("Tool call: schemaGraph (schema={})", schema);
        long start = System.nanoTime();
        try {
            var result = schemaContext.schemaGraph(
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
            description = "Build a compact SQL authoring context from natural-language terms and/or known tables. " +
            "The tool discovers relevant tables/columns, includes constraints and allowed values, " +
            "adds relationships and join paths between selected tables, and can include tiny best-effort samples.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public QueryContext queryContext(
            @McpToolParam(description = "Schema (optional; default schema)", required = false) String schema,
            @McpToolParam(description = "Search terms from the user request, e.g. 'customers order totals'", required = false) String terms,
            @McpToolParam(description = "Comma-separated table names to force-include, e.g. 'customers,orders' (optional)", required = false) String tables,
            @McpToolParam(description = "Include up to 3 sample rows per selected table. Default false.", required = false) Boolean includeSamples,
            @McpToolParam(description = "Maximum tables to include. Default 12, capped at 50.", required = false) Integer maxTables
    ) {
        log.info("Tool call: queryContext (schema={})", schema);
        long start = System.nanoTime();
        try {
            var result = schemaContext.queryContext(
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
            description = "Return a DOT/Graphviz representation of the schema relationship graph. " +
            "Nodes are tables with all columns and types (PK marked with 🔑, FK with →), " +
            "edges include join conditions. " +
            "Use this to visualize the ERD in an external Graphviz tool.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String schemaGraphDot(
            @McpToolParam(description = "Schema (optional; default schema)", required = false) String schema,
            @McpToolParam(description = "Comma-separated table names to include, e.g. 'customers,orders' (optional — all tables if omitted)", required = false) String tables
    ) {
        log.info("Tool call: schemaGraphDot (schema={})", schema);
        long start = System.nanoTime();
        try {
            String result = schemaContext.schemaGraphDot(schema, tables);
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
