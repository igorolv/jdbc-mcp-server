package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.BenchmarkResult;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.TimedQueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.BenchmarkService;
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

    private final BenchmarkService benchmarks;
    private final JsonResponses json;
    private final ToolErrors errors;

    public BenchmarkTools(BenchmarkService benchmarks, JsonResponses json, ToolErrors errors) {
        this.benchmarks = benchmarks;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Benchmark a SQL statement by running it repeatedly: " +
            "'coldRuns' cold executions (default 1) reported separately, then 'warmRuns' (default 3) " +
            "aggregated into min/median/max. " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "'limit' and 'timeoutSeconds' are REQUIRED — unbounded queries are rejected. " +
            "Returns the size of the last result (rows, columns, truncated), not the rows.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public BenchmarkResult benchmarkQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Values for '?' placeholders, in order.", required = false) List<Object> params,
            @McpToolParam(description = "Values for ':name' placeholders, keyed by name.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows per run — required, > 0. Prevents benchmarking an unbounded query.") Integer limit,
            @McpToolParam(description = "Per-run timeout in seconds — required, > 0.") Integer timeoutSeconds,
            @McpToolParam(description = "Number of cold runs (default 1). Executed first.", required = false) Integer coldRuns,
            @McpToolParam(description = "Number of warm runs (default 3). Aggregated into min/median/max.", required = false) Integer warmRuns
    ) {
        log.info("Tool call: benchmarkQuery (sql={}, params={}, namedParams={}, limit={}, timeoutSeconds={}, coldRuns={}, warmRuns={})", sql, params, namedParams, limit, timeoutSeconds, coldRuns, warmRuns);
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
            BenchmarkResult result = benchmarks.benchmark(sql, params, namedParams, limit, timeoutSeconds, cold, warm);
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
            description = "Run a SQL statement once and return the result together with a " +
            "wall-clock elapsed_ms measurement. " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "Where the engine exposes per-statement counters, also attaches a before/after diff of what " +
            "changed during the run (calls, exec time, rows, buffer hits/reads). " +
            "Respects the row limit and per-query timeout.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public TimedQueryResult timedQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Values for '?' placeholders, in order.", required = false) List<Object> params,
            @McpToolParam(description = "Values for ':name' placeholders, keyed by name.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows to return. Default JDBC_MAX_ROWS", required = false) Integer limit,
            @McpToolParam(description = "Per-query timeout in seconds. Default JDBC_QUERY_TIMEOUT_SECONDS", required = false) Integer timeoutSeconds
    ) {
        log.info("Tool call: timedQuery (sql={}, params={}, namedParams={}, limit={}, timeoutSeconds={})", sql, params, namedParams, limit, timeoutSeconds);
        long start = System.nanoTime();
        try {
            TimedQueryResult result = benchmarks.timed(sql, params, namedParams, limit, timeoutSeconds);
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
