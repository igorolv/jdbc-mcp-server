package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlNotAllowedException;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParameterBindingResolver;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Core MCP tool for running read-only SELECT queries. Strictly read-only — write statements are
 * rejected by {@link ReadOnlyGuard} (inside {@link SqlExecutor}) before reaching the database.
 *
 * <p>Belongs to the {@code query} tool group. Explain / plan / validation / lint / lineage tools
 * live in {@link QueryAnalysisTools} ({@code analysis} group). All tool groups are on by default
 * and can be turned off individually with {@code jdbc-mcp.tools.<group>=false}.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "query", havingValue = "true", matchIfMissing = true)
public class QueryTools {

    private static final Logger log = LoggerFactory.getLogger(QueryTools.class);

    private final ConnectionRegistry connections;
    private final ToolErrors errors;

    public QueryTools(ConnectionRegistry connections, ToolErrors errors) {
        this.connections = connections;
        this.errors = errors;
    }

    @McpTool(
            description = "Run a read-only SQL SELECT / WITH / EXPLAIN when actual result rows are needed. Use " +
            "timedQuery for one timed execution or benchmarkQuery for repeated latency measurements. " +
            QueryToolSupport.BINDING_RULES +
            QueryToolSupport.BINDING_EXAMPLES +
            "Sets 'truncated' when the row cap is hit.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public QueryResult executeQuery(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "") String sql,
            @McpToolParam(description = "Values for '?' placeholders, in order.", required = false) List<Object> params,
            @McpToolParam(description = "Values for ':name' placeholders, keyed by name.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Row limit (default JDBC_MAX_ROWS).", required = false) Integer limit,
            @McpToolParam(description = "Timeout in seconds (default JDBC_QUERY_TIMEOUT_SECONDS).", required = false) Integer timeoutSeconds
    ) {
        log.info("Tool call: executeQuery (sql={}, params={}, namedParams={}, limit={}, timeoutSeconds={})", sql, params, namedParams, limit, timeoutSeconds);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            String normalizedSql = QueryToolSupport.normalizeSql(sql);
            QueryResult result = query(ctx.executor(), normalizedSql, params, namedParams, limit, timeoutSeconds);
            ToolLogger.completed(log, "executeQuery", start);
            return result;
        } catch (SqlNotAllowedException e) {
            ToolLogger.failed(log, "executeQuery", start, e.getMessage());
            throw errors.rejectedException(e);
        } catch (SQLException e) {
            ToolLogger.failed(log, "executeQuery", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "executeQuery", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    private QueryResult query(SqlExecutor executor, String sql, List<Object> params,
                              Map<String, Object> namedParams, Integer limit, Integer timeoutSeconds)
            throws SQLException {
        SqlParameterBindingResolver.Binding binding = SqlParameterBindingResolver.resolve(sql, params, namedParams);
        if (binding.namedParams() != null) {
            return executor.queryNamed(sql, binding.namedParams(), limit, timeoutSeconds);
        }
        return executor.query(sql, binding.params(), limit, timeoutSeconds);
    }
}
