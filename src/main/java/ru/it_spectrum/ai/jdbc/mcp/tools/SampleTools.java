package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.sql.SQLException;
import java.util.Collections;

/**
 * Convenience tools that help an LLM explore data quickly.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "sample", havingValue = "true", matchIfMissing = true)
public class SampleTools {

    private static final Logger log = LoggerFactory.getLogger(SampleTools.class);

    private final ConnectionRegistry connections;
    private final JsonResponses json;
    private final ToolErrors errors;

    public SampleTools(ConnectionRegistry connections, JsonResponses json, ToolErrors errors) {
        this.connections = connections;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Preview a small number of actual rows from one known table or view to understand data " +
            "shape and example values. For fields, types and constraints without reading row data, use describeTable.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public QueryResult sampleRows(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "Rows to return (default 10, max 100).", required = false) Integer limit
    ) {
        log.info("Tool call: sampleRows (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        if (table == null || table.isBlank()) {
            ToolLogger.failed(log, "sampleRows", start, "table must be provided");
            throw errors.argumentException("table must be provided");
        }
        int n = limit == null ? 10 : Math.clamp(limit, 1, 100);
        SqlDialect dialect = ctx.dialect();
        String qualified = qualify(dialect, schema, table);
        String sql = dialect.limitQuery("SELECT * FROM " + qualified, n);
        try {
            QueryResult r = ctx.executor().queryInternal(sql, Collections.emptyList(), n);
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

    private String qualify(SqlDialect dialect, String schema, String table) {
        if (schema != null && !schema.isBlank()) {
            requireSimpleIdent(schema);
        }
        requireSimpleIdent(table);
        return dialect.qualify(schema, table);
    }

    /**
     * We accept only simple identifiers (letters/digits/underscores) here — the tool parameters
     * come from an LLM and we do not want to allow arbitrary injection via a "quoted identifier".
     */
    private static void requireSimpleIdent(String id) {
        if (!id.matches("[A-Za-z_][A-Za-z0-9_$#]*")) {
            throw new IllegalArgumentException("Illegal identifier: '" + id + "'");
        }
    }
}
