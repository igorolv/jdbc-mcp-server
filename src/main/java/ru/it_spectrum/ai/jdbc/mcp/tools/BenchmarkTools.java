package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.BenchmarkResult;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.TimedQueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlNotAllowedException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for measuring read-only query cost:
 * <ul>
 *     <li>{@code benchmarkQuery} — repeats the query {@code coldRuns + warmRuns} times and reports
 *         wall-clock min / median / max, plus the size of the last result;</li>
 *     <li>{@code timedQuery} — single execution (same shape as {@code executeQuery}) with a
 *         wall-clock measurement; on PostgreSQL also attaches a before/after diff from
 *         {@code pg_stat_statements}.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "benchmark", havingValue = "true", matchIfMissing = true)
public class BenchmarkTools {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkTools.class);

    private static final String BINDING_RULES = QueryToolSupport.BINDING_RULES;

    private static final String BINDING_EXAMPLES = QueryToolSupport.BINDING_EXAMPLES;

    private final ConnectionRegistry connections;
    private final JsonResponses json;
    private final ToolErrors errors;

    public BenchmarkTools(ConnectionRegistry connections, JsonResponses json, ToolErrors errors) {
        this.connections = connections;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Repeat a read-only SQL SELECT / WITH / EXPLAIN; report cold runs separately " +
            "and aggregate warm runs into min/median/max. " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "Returns the size of the last result (rows, columns, truncated), not the rows.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public BenchmarkResult benchmarkQuery(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "") String sql,
            @McpToolParam(description = "Values for '?' placeholders, in order.", required = false) List<Object> params,
            @McpToolParam(description = "Values for ':name' placeholders, keyed by name.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Row limit per run (> 0).") Integer limit,
            @McpToolParam(description = "Timeout per run in seconds (> 0).") Integer timeoutSeconds,
            @McpToolParam(description = "Cold runs (default 1); executed first.", required = false) Integer coldRuns,
            @McpToolParam(description = "Warm runs (default 3).", required = false) Integer warmRuns
    ) {
        log.info("Tool call: benchmarkQuery (sql={}, params={}, namedParams={}, limit={}, timeoutSeconds={}, coldRuns={}, warmRuns={})", sql, params, namedParams, limit, timeoutSeconds, coldRuns, warmRuns);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        if (limit == null) {
            ToolLogger.failed(log, "benchmarkQuery", start, "limit is required");
            throw errors.argumentException("limit is required");
        }
        if (timeoutSeconds == null) {
            ToolLogger.failed(log, "benchmarkQuery", start, "timeoutSeconds is required");
            throw errors.argumentException("timeoutSeconds is required");
        }
        int cold = coldRuns == null ? 1 : coldRuns;
        int warm = warmRuns == null ? 3 : warmRuns;
        try {
            BenchmarkResult result = ctx.benchmarks().benchmark(sql, params, namedParams, limit, timeoutSeconds, cold, warm);
            ToolLogger.completed(log, "benchmarkQuery", start);
            return result;
        } catch (SqlNotAllowedException e) {
            ToolLogger.failed(log, "benchmarkQuery", start, e.getMessage());
            throw errors.rejectedException(e);
        } catch (SQLException e) {
            ToolLogger.failed(log, "benchmarkQuery", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "benchmarkQuery", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Run one read-only SQL SELECT / WITH / EXPLAIN and return rows plus elapsed_ms. " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "Adds available per-statement counter deltas (calls, execution time, rows, buffer hits/reads).",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public TimedQueryResult timedQuery(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "") String sql,
            @McpToolParam(description = "Values for '?' placeholders, in order.", required = false) List<Object> params,
            @McpToolParam(description = "Values for ':name' placeholders, keyed by name.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Row limit (default JDBC_MAX_ROWS).", required = false) Integer limit,
            @McpToolParam(description = "Timeout in seconds (default JDBC_QUERY_TIMEOUT_SECONDS).", required = false) Integer timeoutSeconds
    ) {
        log.info("Tool call: timedQuery (sql={}, params={}, namedParams={}, limit={}, timeoutSeconds={})", sql, params, namedParams, limit, timeoutSeconds);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            TimedQueryResult result = ctx.benchmarks().timed(sql, params, namedParams, limit, timeoutSeconds);
            ToolLogger.completed(log, "timedQuery", start);
            return result;
        } catch (SqlNotAllowedException e) {
            ToolLogger.failed(log, "timedQuery", start, e.getMessage());
            throw errors.rejectedException(e);
        } catch (SQLException e) {
            ToolLogger.failed(log, "timedQuery", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "timedQuery", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }
}
