package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * SchemaContextTools, and BenchmarkTools.
 */
@Component
public class ToolErrors {

    private static final Logger log = LoggerFactory.getLogger(ToolErrors.class);

    private final JsonResponses json;

    public ToolErrors(JsonResponses json) {
        this.json = json;
    }

    public String sql(SQLException e) {
        log.warn("Tool error [kind=sql]: {}", e.getMessage());
        return error("sql", e.getMessage());
    }

    public RuntimeException sqlException(SQLException e) {
        return toolException(sql(e), e);
    }

    public String argument(IllegalArgumentException e) {
        log.warn("Tool error [kind=argument]: {}", e.getMessage());
        return error("argument", e.getMessage());
    }

    public RuntimeException argumentException(IllegalArgumentException e) {
        return toolException(argument(e), e);
    }

    public String argument(String message) {
        log.warn("Tool error [kind=argument]: {}", message);
        return error("argument", message);
    }

    public RuntimeException argumentException(String message) {
        return toolException(argument(message), null);
    }

    public String rejected(SqlNotAllowedException e) {
        log.warn("Tool error [kind=rejected]: {}", e.getMessage());
        return error("rejected", e.getMessage());
    }

    public RuntimeException rejectedException(SqlNotAllowedException e) {
        return toolException(rejected(e), e);
    }

    public String notFound(String kind, String name) {
        log.warn("Tool error [kind=not_found]: {} {} not found", kind, name);
        return json.write(ToolErrorResponse.notFound(kind, name));
    }

    public RuntimeException notFoundException(String kind, String name) {
        return toolException(notFound(kind, name), null);
    }

    public String unexpected(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        log.error("Tool error [kind=unexpected]: {}", msg, e);
        return error("unexpected", msg);
    }

    public RuntimeException unexpectedException(Throwable e) {
        return toolException(unexpected(e), e);
    }

    public String planParse(IllegalArgumentException e) {
        log.warn("Tool error [kind=plan_parse]: {}", e.getMessage());
        return error("plan_parse", e.getMessage());
    }

    public RuntimeException planParseException(IllegalArgumentException e) {
        return toolException(planParse(e), e);
    }

    private String error(String kind, String message) {
        return json.write(ToolErrorResponse.of(kind, message));
    }

    private RuntimeException toolException(String jsonBody, Throwable cause) {
        return cause == null ? new IllegalStateException(jsonBody) : new IllegalStateException(jsonBody, cause);
    }
}
