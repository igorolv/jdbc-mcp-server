package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
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

    private final BenchmarkService benchmarks;

    public BenchmarkTools(BenchmarkService benchmarks) {
        this.benchmarks = benchmarks;
    }

    @McpTool(description = "Benchmark a read-only SQL statement by running it repeatedly and reporting " +
            "wall-clock latency: the first 'coldRuns' executions (default 1) are reported separately " +
            "as 'cold' (caches cold, plan not primed), then 'warmRuns' executions (default 3) are " +
            "aggregated into min / median / max. " +
            "Use either positional 'params' for '?' placeholders or 'namedParams' for ':name' placeholders. " +
            "Both 'limit' and 'timeoutSeconds' are REQUIRED — unbounded queries are rejected. " +
            "Returns the size of the last result (row count, columns, truncation flag), not the rows. " +
            "All runs are subject to the read-only guard; any write statement is rejected.")
    public String benchmarkQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders (optional)", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders (optional)", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows per run — required, > 0. Prevents benchmarking an unbounded query.") Integer limit,
            @McpToolParam(description = "Per-run timeout in seconds — required, > 0.") Integer timeoutSeconds,
            @McpToolParam(description = "Number of cold runs (default 1). Executed first.", required = false) Integer coldRuns,
            @McpToolParam(description = "Number of warm runs (default 3). Aggregated into min/median/max.", required = false) Integer warmRuns
    ) {
        if (limit == null) return "Invalid argument: limit is required";
        if (timeoutSeconds == null) return "Invalid argument: timeoutSeconds is required";
        int cold = coldRuns == null ? 1 : coldRuns;
        int warm = warmRuns == null ? 3 : warmRuns;
        try {
            Map<String, Object> result = namedParams != null && !namedParams.isEmpty()
                    ? benchmarks.benchmark(sql, namedParams, limit, timeoutSeconds, cold, warm)
                    : benchmarks.benchmark(sql, params, limit, timeoutSeconds, cold, warm);
            return MetadataTools.JsonWriter.write(result);
        } catch (SqlNotAllowedException e) {
            return "Rejected: " + e.getMessage();
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }

    @McpTool(description = "Run a read-only SQL statement once and return the result together with a " +
            "wall-clock elapsed_ms measurement. " +
            "Use either positional 'params' for '?' placeholders or 'namedParams' for ':name' placeholders. " +
            "On PostgreSQL, if the pg_stat_statements extension is installed, also attaches a " +
            "per-queryid diff of counters that changed during the run (calls, total_exec_time_ms, rows, " +
            "shared_blks_hit, shared_blks_read) so you can see what the server actually did. " +
            "Subject to the read-only guard, row limit and per-query timeout — same safety as executeQuery.")
    public String timedQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders (optional)", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders (optional)", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows to return (optional, default JDBC_MAX_ROWS)", required = false) Integer limit,
            @McpToolParam(description = "Per-query timeout in seconds (optional, default JDBC_QUERY_TIMEOUT_SECONDS)", required = false) Integer timeoutSeconds
    ) {
        try {
            Map<String, Object> result = namedParams != null && !namedParams.isEmpty()
                    ? benchmarks.timed(sql, namedParams, limit, timeoutSeconds)
                    : benchmarks.timed(sql, params, limit, timeoutSeconds);
            return MetadataTools.JsonWriter.write(result);
        } catch (SqlNotAllowedException e) {
            return "Rejected: " + e.getMessage();
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }
}
