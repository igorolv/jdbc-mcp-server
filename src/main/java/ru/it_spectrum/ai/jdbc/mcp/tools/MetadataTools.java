package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.format.ResultFormatter;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP tools exposing database metadata: schemas, tables, columns, indexes, foreign keys,
 * view definitions, routines, sequences, and cross-object search.
 */
@Service
public class MetadataTools {

    private final MetadataService metadata;

    public MetadataTools(MetadataService metadata) {
        this.metadata = metadata;
    }

    @McpTool(description = "List all schemas visible to the current user. " +
            "System schemas (pg_catalog, information_schema, SYS, SYSTEM, ...) are excluded by default.")
    public String listSchemas(
            @McpToolParam(description = "Include system schemas. Default false.", required = false) Boolean includeSystem
    ) {
        try {
            List<String> schemas = metadata.listSchemas(Boolean.TRUE.equals(includeSystem));
            return toJsonArray(schemas);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        }
    }

    @McpTool(description = "List tables and views in a schema. " +
            "If 'schema' is omitted, uses JDBC_DEFAULT_SCHEMA or the current schema. " +
            "'namePattern' supports JDBC wildcards ('%' any, '_' one char). " +
            "'types' filters by table type (defaults to TABLE, VIEW, MATERIALIZED VIEW).")
    public String listTables(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Name pattern with JDBC wildcards, e.g. '%user%' (optional)", required = false) String namePattern,
            @McpToolParam(description = "Comma-separated list of types: TABLE,VIEW,MATERIALIZED VIEW,SYSTEM TABLE,GLOBAL TEMPORARY,LOCAL TEMPORARY,ALIAS,SYNONYM (optional)", required = false) String types
    ) {
        try {
            String[] typeArr = parseTypes(types);
            List<Map<String, Object>> rows = metadata.listTables(schema, namePattern, typeArr);
            return ResultFormatter.toJson(toResult(List.of("schema", "name", "type", "remarks"), rows));
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        }
    }

    @McpTool(description = "Describe a table or view completely: columns (name, type, size, nullable, default, remarks), " +
            "primary key, unique constraints, indexes, outgoing foreign keys, and tables that reference this one. " +
            "Returned in one JSON document so the LLM has everything it needs to write a correct query.")
    public String describeTable(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table
    ) {
        try {
            Map<String, Object> info = metadata.describeTable(schema, table);
            return JsonWriter.write(info);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }

    @McpTool(description = "Get a trigger definition/body for one table trigger.")
    public String getTriggerDefinition(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Trigger name") String trigger
    ) {
        try {
            String def = metadata.triggerDefinition(schema, table, trigger);
            return def == null || def.isBlank() ? "(not found)" : def;
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }

    @McpTool(description = "Get the SQL definition of a view or materialized view.")
    public String getViewDefinition(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "View name") String name
    ) {
        try {
            String def = metadata.viewDefinition(schema, name);
            return def == null ? "(not found)" : def;
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        }
    }

    @McpTool(description = "List functions / procedures / packages in a schema. " +
            "For Oracle, packages are listed once (use getRoutineDefinition to get the source of a specific package body).")
    public String listRoutines(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Name pattern (optional, e.g. '%calculate%')", required = false) String namePattern
    ) {
        try {
            QueryResult r = metadata.listRoutines(schema, namePattern);
            return ResultFormatter.toJson(r);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get the source code of a function / procedure / package (body, where applicable). " +
            "On Oracle the result contains the concatenated source lines from ALL_SOURCE.")
    public String getRoutineDefinition(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Routine name") String name
    ) {
        try {
            String source = metadata.routineSource(schema, name);
            return source == null || source.isEmpty() ? "(not found)" : source;
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        }
    }

    @McpTool(description = "List sequences in a schema (or in all schemas if schema is omitted).")
    public String listSequences(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema
    ) {
        try {
            QueryResult r = metadata.listSequences(schema);
            return ResultFormatter.toJson(r);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        }
    }

    @McpTool(description = "Search all non-system database objects by name pattern. " +
            "Case-insensitive substring match. Returns up to 200 matches across tables, views, " +
            "materialized views, routines, packages, sequences and synonyms. " +
            "Useful when the LLM knows a partial name and needs to locate the object and its schema.")
    public String searchObjects(
            @McpToolParam(description = "Name pattern — plain substring (auto-wrapped in %..%) or explicit pattern with % / _") String pattern
    ) {
        try {
            QueryResult r = metadata.searchObjects(pattern);
            return ResultFormatter.toJson(r);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        }
    }

    // ---------------- helpers ----------------

    private String[] parseTypes(String types) {
        if (types == null || types.isBlank()) return null;
        String[] parts = types.split(",");
        List<String> cleaned = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) cleaned.add(t);
        }
        return cleaned.isEmpty() ? null : cleaned.toArray(new String[0]);
    }

    private String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        sb.append("]");
        return sb.toString();
    }

    private QueryResult toResult(List<String> columns, List<Map<String, Object>> rows) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) types.add("text");
        return new QueryResult(columns, types, rows, false, rows.size());
    }

    // Uses ResultFormatter's JSON writer indirectly through QueryResult for consistency,
    // but describeTable has nested structures — we need a tiny recursive JSON writer here.
    static final class JsonWriter {
        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            append(sb, value, 0);
            return sb.toString();
        }

        private static void append(StringBuilder sb, Object v, int depth) {
            if (v == null) {
                sb.append("null");
                return;
            }
            if (v instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('\n').append("  ".repeat(depth + 1));
                    appendString(sb, String.valueOf(e.getKey()));
                    sb.append(": ");
                    append(sb, e.getValue(), depth + 1);
                }
                if (!first) sb.append('\n').append("  ".repeat(depth));
                sb.append('}');
                return;
            }
            if (v instanceof List<?> list) {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    append(sb, list.get(i), depth + 1);
                }
                sb.append(']');
                return;
            }
            if (v instanceof Boolean || v instanceof Number) {
                sb.append(v);
                return;
            }
            appendString(sb, v.toString());
        }

        private static void appendString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\' -> sb.append("\\\\");
                    case '"' -> sb.append("\\\"");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }
}
