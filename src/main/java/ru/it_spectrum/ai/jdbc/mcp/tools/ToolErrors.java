package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.stereotype.Component;
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
@Component
public class ToolErrors {

    private final JsonResponses json;

    public ToolErrors(JsonResponses json) {
        this.json = json;
    }

    public String sql(SQLException e) {
        return error("sql", e.getMessage());
    }

    public String argument(IllegalArgumentException e) {
        return error("argument", e.getMessage());
    }

    public String argument(String message) {
        return error("argument", message);
    }

    public String rejected(SqlNotAllowedException e) {
        return error("rejected", e.getMessage());
    }

    public String notFound(String kind, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", kind + " '" + name + "' not found");
        body.put("kind", "not_found");
        body.put("missing", kind);
        body.put("name", name);
        return json.write(body);
    }

    public String driver(String message) {
        return error("driver", message);
    }

    public String unexpected(Throwable e) {
        return error("unexpected", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    public String planParse(IllegalArgumentException e) {
        return error("plan_parse", e.getMessage());
    }

    private String error(String kind, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("kind", kind);
        return json.write(body);
    }
}
