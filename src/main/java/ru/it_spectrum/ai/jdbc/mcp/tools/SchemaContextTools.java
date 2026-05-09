package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;

import java.sql.SQLException;

/**
 * Higher-level schema context tools for SQL-writing agents.
 */
@Service
public class SchemaContextTools {

    private final SchemaContextService schemaContext;
    private final JsonResponses json;
    private final ToolErrors errors;

    public SchemaContextTools(SchemaContextService schemaContext, JsonResponses json, ToolErrors errors) {
        this.schemaContext = schemaContext;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(description = "Return a compact schema snapshot for writing SQL: tables/views, columns, " +
            "primary keys, foreign keys, indexes, and FK relationship edges. " +
            "Use this before drafting queries when the relevant tables are not known yet. " +
            "Defaults are intentionally compact: maxTables defaults to 50 and is capped at 300.")
    public String schemaOverview(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table/view name pattern with JDBC wildcards, e.g. '%customer%' (optional)", required = false) String namePattern,
            @McpToolParam(description = "Include views and materialized views. Default true.", required = false) Boolean includeViews,
            @McpToolParam(description = "Include per-table row/size/activity stats where available. Default false.", required = false) Boolean includeStats,
            @McpToolParam(description = "Augment relationships with a typed three-layer 'evidence' bundle from the local usage catalog: 'declaredSchema' for catalog FKs, 'observedQuery' (joinSupport + queryUids) when matching equi-joins exist in stored application queries, and 'semanticUsage' (shared business domains/objects/output labels across queries that touch both tables). Observed-only equi-join pairs are appended as new edges with 'relationshipType: observed'. Default: true if the catalog is enabled, else false.", required = false) Boolean includeObserved,
            @McpToolParam(description = "Maximum tables/views to describe. Default 50, capped at 300.", required = false) Integer maxTables
    ) {
        try {
            var result = schemaContext.schemaOverview(
                    schema, namePattern, includeViews, includeStats, includeObserved, maxTables);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Return a compact context around one table: the table itself, FK parent tables, " +
            "optionally child tables that reference it, and relationship edges. " +
            "Use this when a query starts from a known table and needs nearby joins. " +
            "Depth defaults to 1 and is capped at 4.")
    public String tableContext(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Root table or view name") String table,
            @McpToolParam(description = "FK traversal depth. Default 1, capped at 4.", required = false) Integer depth,
            @McpToolParam(description = "Include incoming references from child tables. Default true.", required = false) Boolean includeIncoming,
            @McpToolParam(description = "Include per-table row/size/activity stats where available. Default false.", required = false) Boolean includeStats,
            @McpToolParam(description = "Augment relationships with the three-layer 'evidence' bundle from the local usage catalog (see schemaOverview for the layer breakdown). Default: true if the catalog is enabled, else false.", required = false) Boolean includeObserved
    ) {
        try {
            var result = schemaContext.tableContext(
                    schema, table, depth, includeIncoming, includeStats, includeObserved);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Find FK-based join paths between two tables. " +
            "The graph is traversed in both FK directions and each edge includes a joinCondition. " +
            "Use this when you know the start and target tables but not the intermediate joins. " +
            "maxDepth defaults to 4 and is capped at 4; maxPaths defaults to 5.")
    public String findJoinPaths(
            @McpToolParam(description = "Start schema (optional — defaults to current/default schema)", required = false) String fromSchema,
            @McpToolParam(description = "Start table name") String fromTable,
            @McpToolParam(description = "Target schema (optional — defaults to current/default schema)", required = false) String toSchema,
            @McpToolParam(description = "Target table name") String toTable,
            @McpToolParam(description = "Maximum FK hops. Default 4, capped at 4.", required = false) Integer maxDepth,
            @McpToolParam(description = "Maximum paths to return. Default 5, capped at 25.", required = false) Integer maxPaths,
            @McpToolParam(description = "Maximum schema tables to scan when building the graph. Default 300, capped at 300.", required = false) Integer scanLimit,
            @McpToolParam(description = "Include observed equi-join pairs from the local usage catalog as additional edges in the search graph. Each path step then carries an 'evidence' bundle with 'declaredSchema', 'observedQuery' and 'semanticUsage' layers (any of which may be absent). Default: true if the catalog is enabled, else false.", required = false) Boolean includeObserved
    ) {
        try {
            var result = schemaContext.findJoinPaths(
                    fromSchema, fromTable, toSchema, toTable, maxDepth, maxPaths, scanLimit,
                    includeObserved);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Run a compact schema lint audit for SQL-writing and query-optimization risks. " +
            "Checks include missing primary keys, FK columns without supporting indexes, FK type mismatch, " +
            "nullable unique columns, status/type columns without CHECK, " +
            "orphan *_id columns, missing remarks, isolated tables, and wide tables. " +
            "The optional checks parameter is a comma-separated allow-list; omit it to run the default set.")
    public String schemaLint(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Single table to lint (optional — omit to scan the schema)", required = false) String table,
            @McpToolParam(description = "Comma-separated checks to run, e.g. 'missingPrimaryKey,fkWithoutIndex' (optional)", required = false) String checks,
            @McpToolParam(description = "Maximum schema tables to scan. Default 50, capped at 300.", required = false) Integer maxTables,
            @McpToolParam(description = "Maximum findings to return. Default 200, capped at 1000.", required = false) Integer maxFindings
    ) {
        try {
            var result = schemaContext.schemaLint(
                    schema, table, checks, maxTables, maxFindings);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Return a compact plain-text synopsis of a schema for LLM SQL authoring. " +
            "Summarizes hub tables, fact/detail tables, lookup/reference tables, key relationships, " +
            "enum-like CHECK columns, and brief per-table notes. " +
            "Use this when a full JSON schema overview would be too verbose.")
    public String schemaBrief(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Optional search terms to narrow table discovery; falls back to whole schema if no table matches.", required = false) String terms,
            @McpToolParam(description = "Maximum tables to scan. Default 50, capped at 300.", required = false) Integer maxTables
    ) {
        try {
            return schemaContext.schemaBrief(schema, terms, maxTables);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Return relationship graph metrics for a schema: nodes with incoming/outgoing degree " +
            "and classification, edges, central tables, isolated tables, connected components, cycle hints, " +
            "and optionally the shortest path between two tables.")
    public String schemaGraph(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Maximum tables to scan. Default 50, capped at 300.", required = false) Integer maxTables,
            @McpToolParam(description = "Optional start table for shortestPath.", required = false) String fromTable,
            @McpToolParam(description = "Optional target table for shortestPath.", required = false) String toTable,
            @McpToolParam(description = "Maximum hops for shortestPath. Default 4, capped at 4.", required = false) Integer maxDepth
    ) {
        try {
            var result = schemaContext.schemaGraph(
                    schema, maxTables, fromTable, toTable, maxDepth);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Build a compact SQL authoring context from natural-language terms and/or known tables. " +
            "The tool discovers relevant tables/columns, includes constraints and allowed values, " +
            "adds relationships and join paths between selected tables, and can include tiny best-effort samples.")
    public String queryContext(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Search terms from the user request, e.g. 'customers order totals'", required = false) String terms,
            @McpToolParam(description = "Comma-separated table names to force-include, e.g. 'customers,orders' (optional)", required = false) String tables,
            @McpToolParam(description = "Include up to 3 sample rows per selected table. Default false.", required = false) Boolean includeSamples,
            @McpToolParam(description = "Maximum tables to include. Default 12, capped at 50.", required = false) Integer maxTables
    ) {
        try {
            var result = schemaContext.queryContext(
                    schema, terms, tables, includeSamples, maxTables);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Return a DOT/Graphviz representation of the schema relationship graph. " +
            "Nodes are tables with all columns and types (PK marked with 🔑, FK with →), " +
            "edges include join conditions. " +
            "Use this to visualize the ERD in an external Graphviz tool.")
    public String schemaGraphDot(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Comma-separated table names to include, e.g. 'customers,orders' (optional — all tables if omitted)", required = false) String tables
    ) {
        try {
            return schemaContext.schemaGraphDot(schema, tables);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }
}
