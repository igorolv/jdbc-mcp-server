package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;

import java.sql.SQLException;
import java.util.Map;

/**
 * Higher-level schema context tools for SQL-writing agents.
 */
@Service
public class SchemaContextTools {

    private final SchemaContextService schemaContext;

    public SchemaContextTools(SchemaContextService schemaContext) {
        this.schemaContext = schemaContext;
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
            @McpToolParam(description = "Maximum tables/views to describe. Default 50, capped at 300.", required = false) Integer maxTables
    ) {
        try {
            Map<String, Object> result = schemaContext.schemaOverview(
                    schema, namePattern, includeViews, includeStats, maxTables);
            return MetadataTools.JsonWriter.write(result);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
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
            @McpToolParam(description = "Include per-table row/size/activity stats where available. Default false.", required = false) Boolean includeStats
    ) {
        try {
            Map<String, Object> result = schemaContext.tableContext(
                    schema, table, depth, includeIncoming, includeStats);
            return MetadataTools.JsonWriter.write(result);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
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
            @McpToolParam(description = "Maximum schema tables to scan. Default 300, capped at 300.", required = false) Integer maxTables
    ) {
        try {
            Map<String, Object> result = schemaContext.findJoinPaths(
                    fromSchema, fromTable, toSchema, toTable, maxDepth, maxPaths, maxTables);
            return MetadataTools.JsonWriter.write(result);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }
}
