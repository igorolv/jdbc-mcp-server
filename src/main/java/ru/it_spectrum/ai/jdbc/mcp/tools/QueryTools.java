package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
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

    private final SqlExecutor executor;
    private final ToolErrors errors;

    public QueryTools(SqlExecutor executor, ToolErrors errors) {
        this.executor = executor;
        this.errors = errors;
    }

    @McpTool(
            description = "Execute a read-only SQL SELECT / WITH / EXPLAIN statement and return the result. " +
            "Only pure read statements are allowed — write operations (INSERT, UPDATE, DELETE, DDL, etc.) " +
            "are rejected before being sent to the database. " +
            QueryToolSupport.BINDING_RULES +
            QueryToolSupport.BINDING_EXAMPLES +
            "Results are truncated to 'limit' rows (default JDBC_MAX_ROWS) with a 'truncated' marker.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public QueryResult executeQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Values for '?' placeholders, in order.", required = false) List<Object> params,
            @McpToolParam(description = "Values for ':name' placeholders, keyed by name.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows to return (optional, default JDBC_MAX_ROWS)", required = false) Integer limit,
            @McpToolParam(description = "Per-query timeout in seconds (optional, default JDBC_QUERY_TIMEOUT_SECONDS)", required = false) Integer timeoutSeconds
    ) {
        log.info("Tool call: executeQuery (sql={}, params={}, namedParams={}, limit={}, timeoutSeconds={})", sql, params, namedParams, limit, timeoutSeconds);
        long start = System.nanoTime();
        try {
            String normalizedSql = QueryToolSupport.normalizeSql(sql);
            QueryResult result = query(normalizedSql, params, namedParams, limit, timeoutSeconds);
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

    private QueryResult query(String sql, List<Object> params, Map<String, Object> namedParams,
                              Integer limit, Integer timeoutSeconds) throws SQLException {
        SqlParameterBindingResolver.Binding binding = SqlParameterBindingResolver.resolve(sql, params, namedParams);
        if (binding.namedParams() != null) {
            return executor.queryNamed(sql, binding.namedParams(), limit, timeoutSeconds);
        }
        return executor.query(sql, binding.params(), limit, timeoutSeconds);
    }
}
