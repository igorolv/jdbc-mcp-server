package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.SQLException;
import java.util.Collections;

/**
 * Convenience tools that help an LLM explore data quickly.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "sample", havingValue = "true", matchIfMissing = true)
public class SampleTools {

    private static final Logger log = LoggerFactory.getLogger(SampleTools.class);

    private final SqlExecutor executor;
    private final SqlDialect dialect;
    private final JsonResponses json;
    private final ToolErrors errors;

    public SampleTools(SqlExecutor executor, SqlDialect dialect, JsonResponses json, ToolErrors errors) {
        this.executor = executor;
        this.dialect = dialect;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Return limited rows from a table or view.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public QueryResult sampleRows(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "Rows to return (default 10, max 100).", required = false) Integer limit
    ) {
        log.info("Tool call: sampleRows (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        if (table == null || table.isBlank()) {
            ToolLogger.failed(log, "sampleRows", start, "table must be provided");
            throw errors.argumentException("table must be provided");
        }
        int n = limit == null ? 10 : Math.clamp(limit, 1, 100);
        String qualified = qualify(schema, table);
        String sql = dialect.limitQuery("SELECT * FROM " + qualified, n);
        try {
            QueryResult r = executor.queryInternal(sql, Collections.emptyList(), n);
            ToolLogger.completed(log, "sampleRows", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "sampleRows", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "sampleRows", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    // ---------------- helpers ----------------

    private String qualify(String schema, String table) {
        if (schema == null || schema.isBlank()) {
            return quoteIdent(table);
        }
        return quoteIdent(schema) + "." + quoteIdent(table);
    }

    /**
     * Quote an identifier in a dialect-appropriate way. We accept only simple identifiers
     * (letters/digits/underscores) here — the tool parameters come from an LLM and we do
     * not want to allow arbitrary injection via a "quoted identifier".
     */
    private String quoteIdent(String id) {
        if (!id.matches("[A-Za-z_][A-Za-z0-9_$#]*")) {
            throw new IllegalArgumentException("Illegal identifier: '" + id + "'");
        }
        if (dialect.kind() == DatabaseKind.ORACLE) {
            // Oracle stores unquoted identifiers in upper case; use the name as-is unquoted
            // so existing tables (created without quotes) resolve normally.
            return id;
        }
        if (dialect.kind() == DatabaseKind.MSSQL) {
            return "[" + id + "]";
        }
        return "\"" + id + "\"";
    }
}
