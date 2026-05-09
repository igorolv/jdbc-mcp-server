package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.jdbc.mcp.model.ToolErrorResponse;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlNotAllowedException;

import java.sql.SQLException;

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
        return json.write(ToolErrorResponse.notFound(kind, name));
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
        return json.write(ToolErrorResponse.of(kind, message));
    }
}
