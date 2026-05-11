package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.BenchmarkResult;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.PgStatStatementEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.PgStatStatements;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.ResultSize;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.TimedQueryResult;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.TimingStats;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measurement utilities on top of {@link SqlExecutor}
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    /** Hard bound on the total number of runs to keep a single call well-behaved. */
    static final int MAX_RUNS = 20;

    private final SqlExecutor executor;
    private final SqlDialect dialect;

    public BenchmarkService(SqlExecutor executor, SqlDialect dialect) {
        this.executor = executor;
        this.dialect = dialect;
    }

    public BenchmarkResult benchmark(String sql, List<Object> params, Map<String, Object> namedParams,
                                     int limit, int timeoutSeconds,
                                     int coldRuns, int warmRuns) throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit is required and must be > 0");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds is required and must be > 0");
        }
        if (coldRuns < 0 || warmRuns < 0) {
            throw new IllegalArgumentException("coldRuns and warmRuns must be >= 0");
        }
        int totalRuns = coldRuns + warmRuns;
        if (totalRuns <= 0) {
            throw new IllegalArgumentException("coldRuns + warmRuns must be > 0");
        }
        if (totalRuns > MAX_RUNS) {
            throw new IllegalArgumentException("coldRuns + warmRuns must not exceed " + MAX_RUNS);
        }

        SqlParameterBindingResolver.Binding binding = SqlParameterBindingResolver.resolve(sql, params, namedParams);
        boolean hasNamed = binding.namedParams() != null;

        List<Double> cold = new ArrayList<>(coldRuns);
        List<Double> warm = new ArrayList<>(warmRuns);
        List<Double> all  = new ArrayList<>(totalRuns);
        QueryResult last = null;

        for (int i = 0; i < coldRuns; i++) {
            long t0 = System.nanoTime();
            last = hasNamed
                    ? executor.queryNamed(sql, binding.namedParams(), limit, timeoutSeconds)
                    : executor.query(sql, binding.params(), limit, timeoutSeconds);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            cold.add(ms);
            all.add(ms);
        }
        for (int i = 0; i < warmRuns; i++) {
            long t0 = System.nanoTime();
            last = hasNamed
                    ? executor.queryNamed(sql, binding.namedParams(), limit, timeoutSeconds)
                    : executor.query(sql, binding.params(), limit, timeoutSeconds);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            warm.add(ms);
            all.add(ms);
        }

        ResultSize resultSize = null;
        if (last != null) {
            resultSize = new ResultSize(
                    last.rowCount(),
                    last.truncated(),
                    last.columns(),
                    last.columnTypes());
        }
        return new BenchmarkResult(
                dialect.kind().name().toLowerCase(Locale.ROOT),
                totalRuns,
                coldRuns,
                warmRuns,
                limit,
                timeoutSeconds,
                toStats(cold),
                toStats(warm),
                round(all),
                resultSize,
                "Wall-clock timings include JDBC round-trip and row materialization. " +
                        "The first (cold) run exercises uncached plans and pages; warm runs reflect " +
                        "steady-state latency with buffers populated.");
    }

    public TimedQueryResult timed(String sql, List<Object> params, Map<String, Object> namedParams,
                                  Integer limit, Integer timeoutSeconds) throws SQLException {
        SqlParameterBindingResolver.Binding binding = SqlParameterBindingResolver.resolve(sql, params, namedParams);
        boolean hasNamed = binding.namedParams() != null;

        Map<String, Counters> before = snapshotPss();
        long t0 = System.nanoTime();
        QueryResult result = hasNamed
                ? executor.queryNamed(sql, binding.namedParams(), limit, timeoutSeconds)
                : executor.query(sql, binding.params(), limit, timeoutSeconds);
        double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
        Map<String, Counters> after = snapshotPss();

        PgStatStatements pss;
        if (before == null) {
            pss = new PgStatStatements(
                    false,
                    null,
                    null,
                    "pg_stat_statements is not installed or not supported on this engine. " +
                    "On PostgreSQL: CREATE EXTENSION pg_stat_statements; and add it to " +
                    "shared_preload_libraries.");
        } else {
            List<PgStatStatementEntry> diffs = diff(before, after);
            pss = new PgStatStatements(
                    true,
                    diffs.size(),
                    diffs,
                    "Diff reports pg_stat_statements rows whose counters changed during the run. " +
                            "Background activity in other sessions may also appear.");
        }
        return new TimedQueryResult(
                dialect.kind().name().toLowerCase(Locale.ROOT),
                round1(elapsedMs),
                result.rowCount(),
                result.truncated(),
                result.columns(),
                result.columnTypes(),
                result.rows(),
                pss);
    }

    // ---------------- pg_stat_statements helpers ----------------

    /**
     * Returns {@code null} if pg_stat_statements is not available (non-PG engine, extension
     * missing, or the snapshot query fails). Otherwise, a map from {@code queryid} to counters.
     */
    private Map<String, Counters> snapshotPss() {
        String availSql = dialect.pgStatStatementsAvailabilityQuery();
        String snapSql  = dialect.pgStatStatementsSnapshotQuery();
        if (availSql == null || snapSql == null) {
            return null;
        }
        try {
            QueryResult avail = executor.queryInternal(availSql, Collections.emptyList(), 1);
            if (avail.rows().isEmpty()) {
                return null;
            }
            QueryResult snap = executor.queryInternal(snapSql, Collections.emptyList(), 100_000);
            Map<String, Counters> out = new LinkedHashMap<>(snap.rows().size() * 2);
            for (Map<String, Object> row : snap.rows()) {
                String queryid = asString(getCI(row, "queryid"));
                if (queryid == null) continue;
                Counters c = new Counters(
                        asString(getCI(row, "query")),
                        toLong(getCI(row, "calls")),
                        toDouble(getCI(row, "total_exec_time_ms")),
                        toLong(getCI(row, "rows")),
                        toLong(getCI(row, "shared_blks_hit")),
                        toLong(getCI(row, "shared_blks_read"))
                );
                out.put(queryid, c);
            }
            return out;
        } catch (SQLException e) {
            log.debug("pg_stat_statements snapshot failed (likely permission or missing extension): {}",
                    e.getMessage());
            return null;
        }
    }

    private List<PgStatStatementEntry> diff(Map<String, Counters> before, Map<String, Counters> after) {
        List<PgStatStatementEntry> out = new ArrayList<>();
        String availSig = signature(dialect.pgStatStatementsAvailabilityQuery());
        String snapSig  = signature(dialect.pgStatStatementsSnapshotQuery());
        for (Map.Entry<String, Counters> e : after.entrySet()) {
            Counters a = e.getValue();
            Counters b = before.get(e.getKey());
            long dCalls = a.calls - (b == null ? 0 : b.calls);
            if (dCalls <= 0) continue;
            // Hide our own snapshot / availability probes — they run through this service and
            // otherwise dominate the diff.
            String qSig = signature(a.query);
            if (qSig.equals(availSig) || qSig.equals(snapSig)) continue;

            out.add(new PgStatStatementEntry(
                    trimQuery(a.query),
                    dCalls,
                    round1(a.totalExecTimeMs - (b == null ? 0.0 : b.totalExecTimeMs)),
                    a.rows - (b == null ? 0 : b.rows),
                    a.sharedBlksHit - (b == null ? 0 : b.sharedBlksHit),
                    a.sharedBlksRead - (b == null ? 0 : b.sharedBlksRead)));
        }
        out.sort((x, y) -> Double.compare(
                y.deltaTotalExecTimeMs(),
                x.deltaTotalExecTimeMs()));
        return out;
    }

    /** Short, whitespace-normalized fingerprint so we can recognize our own probes. */
    private static String signature(String sql) {
        if (sql == null) return "";
        String norm = sql.replaceAll("\\s+", " ").trim();
        return norm.length() > 64 ? norm.substring(0, 64) : norm;
    }

    private static String trimQuery(String q) {
        if (q == null) return null;
        String norm = q.replaceAll("\\s+", " ").trim();
        return norm.length() > 200 ? norm.substring(0, 200) + "..." : norm;
    }

    // ---------------- stats helpers ----------------

    static TimingStats toStats(List<Double> samples) {
        if (samples.isEmpty()) {
            return new TimingStats(0, null, null, null);
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        double min = sorted.getFirst();
        double max = sorted.getLast();
        double median;
        int n = sorted.size();
        if (n % 2 == 1) {
            median = sorted.get(n / 2);
        } else {
            median = (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }
        return new TimingStats(samples.size(), round1(min), round1(median), round1(max));
    }

    private static List<Double> round(List<Double> samples) {
        List<Double> out = new ArrayList<>(samples.size());
        for (Double d : samples) out.add(round1(d));
        return out;
    }

    private static double round1(double v) {
        // One decimal place is enough for millisecond reporting and keeps JSON stable.
        return Math.round(v * 10.0) / 10.0;
    }

    private static Object getCI(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        v = row.get(key.toUpperCase(Locale.ROOT));
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            try {
                return Math.round(Double.parseDouble(v.toString().trim()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Snapshot counter tuple for a single pg_stat_statements row. */
    private record Counters(String query,
                            long calls,
                            double totalExecTimeMs,
                            long rows,
                            long sharedBlksHit,
                            long sharedBlksRead) {}
}
