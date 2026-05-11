package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
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
public class BenchmarkTools {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkTools.class);

    private static final String BINDING_RULES =
            "Binding rules: if SQL has no placeholders, omit both 'params' and 'namedParams'. " +
            "If SQL contains '?', pass values in 'params' only, in placeholder order. " +
            "If SQL contains named placeholders in the form ':paramName' such as ':userId' or ':status', " +
            "pass values in 'namedParams' only. " +
            "Never mix '?' and named placeholders in the same SQL statement, and never pass both argument styles. ";

    private static final String BINDING_EXAMPLES =
            "Examples: positional -> sql='SELECT * FROM events WHERE status = ?', params=['OK']; " +
            "named -> sql='SELECT * FROM events WHERE status = :status', namedParams={status: 'OK'}. ";

    private final BenchmarkService benchmarks;
    private final JsonResponses json;
    private final ToolErrors errors;

    public BenchmarkTools(BenchmarkService benchmarks, JsonResponses json, ToolErrors errors) {
        this.benchmarks = benchmarks;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(description = "Benchmark a read-only SQL statement by running it repeatedly and reporting " +
            "wall-clock latency: the first 'coldRuns' executions (default 1) are reported separately " +
            "as 'cold' (caches cold, plan not primed), then 'warmRuns' executions (default 3) are " +
            "aggregated into min / median / max. " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "Both 'limit' and 'timeoutSeconds' are REQUIRED — unbounded queries are rejected. " +
            "Returns the size of the last result (row count, columns, truncation flag), not the rows. " +
            "All runs are subject to the read-only guard; any write statement is rejected.")
    public String benchmarkQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders, in order. Required when SQL contains '?'.", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders. Required when SQL contains ':name'.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows per run — required, > 0. Prevents benchmarking an unbounded query.") Integer limit,
            @McpToolParam(description = "Per-run timeout in seconds — required, > 0.") Integer timeoutSeconds,
            @McpToolParam(description = "Number of cold runs (default 1). Executed first.", required = false) Integer coldRuns,
            @McpToolParam(description = "Number of warm runs (default 3). Aggregated into min/median/max.", required = false) Integer warmRuns
    ) {
        log.info("Tool call: benchmarkQuery (sql={}, params={}, namedParams={}, limit={}, timeoutSeconds={}, coldRuns={}, warmRuns={})", sql, params, namedParams, limit, timeoutSeconds, coldRuns, warmRuns);
        long start = System.nanoTime();
        if (limit == null) {
            ToolLogger.failed(log, "benchmarkQuery", start, "limit is required");
            return errors.argument("limit is required");
        }
        if (timeoutSeconds == null) {
            ToolLogger.failed(log, "benchmarkQuery", start, "timeoutSeconds is required");
            return errors.argument("timeoutSeconds is required");
        }
        int cold = coldRuns == null ? 1 : coldRuns;
        int warm = warmRuns == null ? 3 : warmRuns;
        try {
            BenchmarkResult result = benchmarks.benchmark(sql, params, namedParams, limit, timeoutSeconds, cold, warm);
            ToolLogger.completed(log, "benchmarkQuery", start);
            return json.write(result);
        } catch (SqlNotAllowedException e) {
            ToolLogger.failed(log, "benchmarkQuery", start, e.getMessage());
            return errors.rejected(e);
        } catch (SQLException e) {
            ToolLogger.failed(log, "benchmarkQuery", start, e.getMessage());
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "benchmarkQuery", start, e.getMessage());
            return errors.argument(e);
        }
    }

    @McpTool(description = "Run a read-only SQL statement once and return the result together with a " +
            "wall-clock elapsed_ms measurement. " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "On PostgreSQL, if the pg_stat_statements extension is installed, also attaches a " +
            "per-queryid diff of counters that changed during the run (calls, total_exec_time_ms, rows, " +
            "shared_blks_hit, shared_blks_read) so you can see what the server actually did. " +
            "Subject to the read-only guard, row limit and per-query timeout — same safety as executeQuery.")
    public String timedQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders, in order. Required when SQL contains '?'.", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders. Required when SQL contains ':name'.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows to return (optional, default JDBC_MAX_ROWS)", required = false) Integer limit,
            @McpToolParam(description = "Per-query timeout in seconds (optional, default JDBC_QUERY_TIMEOUT_SECONDS)", required = false) Integer timeoutSeconds
    ) {
        log.info("Tool call: timedQuery (sql={}, params={}, namedParams={}, limit={}, timeoutSeconds={})", sql, params, namedParams, limit, timeoutSeconds);
        long start = System.nanoTime();
        try {
            TimedQueryResult result = benchmarks.timed(sql, params, namedParams, limit, timeoutSeconds);
            ToolLogger.completed(log, "timedQuery", start);
            return json.write(result);
        } catch (SqlNotAllowedException e) {
            ToolLogger.failed(log, "timedQuery", start, e.getMessage());
            return errors.rejected(e);
        } catch (SQLException e) {
            ToolLogger.failed(log, "timedQuery", start, e.getMessage());
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "timedQuery", start, e.getMessage());
            return errors.argument(e);
        }
    }
}
