package ru.it_spectrum.ai.jdbc.mcp.tools;

import ru.it_spectrum.ai.jdbc.mcp.sql.SqlNotAllowedException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for error responses returned by MCP tools.
 *
 * <p>All tools return JSON of the shape:
 * <pre>{"error": "...", "kind": "sql|argument|rejected|not_found|driver"}</pre>
 *
 * <p>Helpers for the most common cases — call them instead of building strings inline so the
 * format stays consistent across QueryTools, MetadataTools, StatsTools, DistributionTools,
 * SchemaContextTools, BenchmarkTools and SnapshotTools.
 */
public final class ToolErrors {

    private ToolErrors() {
    }

    public static String sql(SQLException e) {
        return error("sql", e.getMessage());
    }

    public static String argument(IllegalArgumentException e) {
        return error("argument", e.getMessage());
    }

    public static String argument(String message) {
        return error("argument", message);
    }

    public static String rejected(SqlNotAllowedException e) {
        return error("rejected", e.getMessage());
    }

    public static String notFound(String kind, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", kind + " '" + name + "' not found");
        body.put("kind", "not_found");
        body.put("missing", kind);
        body.put("name", name);
        return JsonWriter.write(body);
    }

    public static String driver(String message) {
        return error("driver", message);
    }

    public static String unexpected(Throwable e) {
        return error("unexpected", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    public static String planParse(IllegalArgumentException e) {
        return error("plan_parse", e.getMessage());
    }

    private static String error(String kind, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("kind", kind);
        return JsonWriter.write(body);
    }
}
