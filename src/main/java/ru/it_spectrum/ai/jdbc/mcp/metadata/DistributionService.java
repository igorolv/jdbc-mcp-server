package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.ColumnDistribution;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.ColumnHistogram;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.ColumnStats;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.JoinCardinality;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.NullRatio;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.SelectivityEstimate;
import ru.it_spectrum.ai.jdbc.mcp.plan.ParsedPlan;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanNode;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanParser;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Column-level selectivity and distribution analyses that the optimiser-flavoured
 * {@link StatsService} deliberately stays out of.
 *
 * <p>Answers "is this predicate going to filter anything?" / "how skewed is this column?" /
 * "will this join return 1 K rows or 1 - information an LLM needs to choose an index
 * order, rewrite a join, or decide whether a partial index makes sense.
 *
 * <ul>
 *     <li>{@code columnDistribution} — top-N most frequent values + their share of the table;</li>
 *     <li>{@code columnHistogram} — quantiles (P25/P50/P75/P90/P95/P99) for orderable columns;</li>
 *     <li>{@code nullRatio} — null / non-null ratio per column in one pass;</li>
 *     <li>{@code estimateSelectivity} — planner's estimate for a predicate, without running the query;</li>
 *     <li>{@code joinCardinality} — planner's estimate for a join, without running it.</li>
 * </ul>
 */
@Service
public class DistributionService {

    private static final Logger log = LoggerFactory.getLogger(DistributionService.class);

    /** Simple identifier pattern - same shape as {@code SampleTools.quoteIdent}. */
    private static final Pattern SIMPLE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    private final SqlExecutor executor;
    private final SqlDialect dialect;
    private final JdbcProperties properties;
    private final PlanParser planParser;
    private final SchemaResolver schemaResolver;

    @Autowired
    public DistributionService(SqlExecutor executor, SqlDialect dialect,
                               JdbcProperties properties, PlanParser planParser) {
        this(executor, dialect, properties, planParser,
                new SchemaResolver(properties, executor, dialect));
    }

    public DistributionService(SqlExecutor executor, SqlDialect dialect,
                               JdbcProperties properties, PlanParser planParser,
                               SchemaResolver schemaResolver) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
        this.planParser = planParser;
        this.schemaResolver = schemaResolver;
    }

    // ---------------- columnStats ----------------

    /**
     * Returns basic single-column extremes - total rows, non-null rows, distinct value count,
     * min and max - in one aggregate scan. Cheaper than {@link #columnHistogram} when only the
     * extremes (and not the spread) are needed.
     */
    public ColumnStats columnStats(String schema, String table, String column) throws SQLException {
        requireIdent("table", table);
        requireIdent("column", column);
        String qTable = qualify(schema, table);
        String qCol = quoteIdent(column);
        String sql = """
                SELECT COUNT(*)            AS total_rows,
                       COUNT(%s)           AS non_null_rows,
                       COUNT(DISTINCT %s)  AS distinct_values,
                       MIN(%s)             AS min_value,
                       MAX(%s)             AS max_value
                FROM %s
                """.formatted(qCol, qCol, qCol, qCol, qTable);
        QueryResult r = executor.queryInternal(sql, Collections.emptyList(), 1);
        String effectiveSchema = resolveSchema(schema);
        if (r.rows().isEmpty()) {
            return new ColumnStats(effectiveSchema, table, column, 0L, 0L, 0L, null, null);
        }
        Map<String, Object> row = r.rows().get(0);
        return new ColumnStats(effectiveSchema, table, column,
                toLong(getCI(row, "total_rows")),
                toLong(getCI(row, "non_null_rows")),
                toLong(getCI(row, "distinct_values")),
                getCI(row, "min_value"),
                getCI(row, "max_value"));
    }

    // ---------------- columnDistribution ----------------

    /**
     * Returns the top-{@code topN} most frequent values of {@code column} together with their
     * share of the total row count. Lets the caller detect heavy data skew - e.g. "70 % of
     * rows have status='OK'" - which makes a non-partial index on {@code status} nearly useless.
     */
    public ColumnDistribution columnDistribution(String schema, String table,
                                                  String column, Integer topN) throws SQLException {
        requireIdent("table", table);
        requireIdent("column", column);
        int n = (topN == null || topN <= 0) ? 20 : Math.min(topN, 1000);

        String qTable = qualify(schema, table);
        String qCol   = quoteIdent(column);
        String baseSql = "SELECT " + qCol + " AS value, COUNT(*) AS frequency " +
                "FROM " + qTable + " GROUP BY " + qCol +
                " ORDER BY COUNT(*) DESC, " + qCol + " ASC";
        String sql = dialect.limitQuery(baseSql, n);

        QueryResult r = executor.queryInternal(sql, Collections.emptyList(), n);

        long totalRows = fetchTotalRows(qTable);
        long totalFromTop = 0L;
        List<ColumnDistribution.ValueEntry> values = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            long freq = toLong(getCI(row, "frequency"));
            totalFromTop += freq;
            values.add(new ColumnDistribution.ValueEntry(
                    getCI(row, "value"), freq, ratio(freq, totalRows)));
        }

        long other = Math.max(0L, totalRows - totalFromTop);

        return new ColumnDistribution(resolveSchema(schema), table, column, n,
                totalRows, totalFromTop, ratio(totalFromTop, totalRows),
                other, ratio(other, totalRows), values);
    }

    // ---------------- columnHistogram ----------------

    /**
     * Computes min/max plus P25/P50/P75/P90/P95/P99 for the given column.
     *
     * <p>Uses {@code WITHIN GROUP (ORDER BY ...)} percentile functions. PostgreSQL and Oracle
     * expose these as aggregates; SQL Server exposes them as window functions, so the query shape
     * is dialect-specific. For numeric types we use {@code percentile_cont} (interpolated); for
     * any other orderable type we use {@code percentile_disc}, which returns an actual existing
     * value and tolerates non-numeric sorts on engines that support them.
     */
    public ColumnHistogram columnHistogram(String schema, String table, String column) throws SQLException {
        requireIdent("table", table);
        requireIdent("column", column);
        String effectiveSchema = resolveSchema(schema);

        ColumnType type = fetchColumnType(effectiveSchema, table, column);
        String qTable = qualify(schema, table);
        String qCol   = quoteIdent(column);
        String pct = type.numeric ? "percentile_cont" : "percentile_disc";

        String sql = dialect.kind() == DatabaseKind.MSSQL
                ? sqlServerHistogramSql(qTable, qCol, pct)
                : "SELECT COUNT(*) AS total_rows, " +
                    "COUNT(" + qCol + ") AS non_null_rows, " +
                    "MIN(" + qCol + ") AS min_value, " +
                    "MAX(" + qCol + ") AS max_value, " +
                    pct + "(0.25) WITHIN GROUP (ORDER BY " + qCol + ") AS p25, " +
                    pct + "(0.5)  WITHIN GROUP (ORDER BY " + qCol + ") AS p50, " +
                    pct + "(0.75) WITHIN GROUP (ORDER BY " + qCol + ") AS p75, " +
                    pct + "(0.9)  WITHIN GROUP (ORDER BY " + qCol + ") AS p90, " +
                    pct + "(0.95) WITHIN GROUP (ORDER BY " + qCol + ") AS p95, " +
                    pct + "(0.99) WITHIN GROUP (ORDER BY " + qCol + ") AS p99 " +
                    "FROM " + qTable;

        QueryResult r = executor.queryInternal(sql, Collections.emptyList(), 1);

        if (r.rows().isEmpty()) {
            return new ColumnHistogram(effectiveSchema, table, column,
                    type.typeName, pct, 0L, 0L, 0L, 0.0,
                    null, null, null, null, null, null, null, null);
        }
        Map<String, Object> row = r.rows().get(0);
        long total   = toLong(getCI(row, "total_rows"));
        long nonNull = toLong(getCI(row, "non_null_rows"));
        long nulls   = total - nonNull;
        return new ColumnHistogram(effectiveSchema, table, column,
                type.typeName, pct, total, nonNull, nulls, ratio(nulls, total),
                getCI(row, "min_value"), getCI(row, "max_value"),
                getCI(row, "p25"), getCI(row, "p50"), getCI(row, "p75"),
                getCI(row, "p90"), getCI(row, "p95"), getCI(row, "p99"));
    }

    private String sqlServerHistogramSql(String qTable, String qCol, String pct) {
        return """
                WITH base AS (
                    SELECT %1$s AS v
                    FROM %2$s
                ),
                stats AS (
                    SELECT COUNT(*) AS total_rows,
                           COUNT(v) AS non_null_rows,
                           MIN(v) AS min_value,
                           MAX(v) AS max_value
                    FROM base
                ),
                pct_values AS (
                    SELECT DISTINCT
                           %3$s(0.25) WITHIN GROUP (ORDER BY v) OVER () AS p25,
                           %3$s(0.5)  WITHIN GROUP (ORDER BY v) OVER () AS p50,
                           %3$s(0.75) WITHIN GROUP (ORDER BY v) OVER () AS p75,
                           %3$s(0.9)  WITHIN GROUP (ORDER BY v) OVER () AS p90,
                           %3$s(0.95) WITHIN GROUP (ORDER BY v) OVER () AS p95,
                           %3$s(0.99) WITHIN GROUP (ORDER BY v) OVER () AS p99
                    FROM base
                    WHERE v IS NOT NULL
                )
                SELECT stats.total_rows,
                       stats.non_null_rows,
                       stats.min_value,
                       stats.max_value,
                       pct.p25,
                       pct.p50,
                       pct.p75,
                       pct.p90,
                       pct.p95,
                       pct.p99
                FROM stats
                OUTER APPLY (SELECT TOP (1) * FROM pct_values) pct
                """.formatted(qCol, qTable, pct);
    }

    // ---------------- nullRatio ----------------

    /**
     * One pass over the table computes the null / non-null counts for every column at once,
     * so the LLM can see which columns are sparse (and therefore candidates for a partial index)
     * without issuing N queries.
     */
    public NullRatio nullRatio(String schema, String table) throws SQLException {
        requireIdent("table", table);
        String effectiveSchema = resolveSchema(schema);

        List<String> columnNames = fetchColumnNames(effectiveSchema, table);

        if (columnNames.isEmpty()) {
            return new NullRatio(effectiveSchema, table, 0L, List.of());
        }

        String qTable = qualify(schema, table);
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) AS total_rows");
        for (int i = 0; i < columnNames.size(); i++) {
            sb.append(", COUNT(").append(quoteIdent(columnNames.get(i))).append(") AS nn_").append(i);
        }
        sb.append(" FROM ").append(qTable);

        QueryResult r = executor.queryInternal(sb.toString(), Collections.emptyList(), 1);

        long total = 0L;
        if (!r.rows().isEmpty()) {
            total = toLong(getCI(r.rows().get(0), "total_rows"));
        }
        List<NullRatio.ColumnEntry> cols = new ArrayList<>(columnNames.size());
        if (!r.rows().isEmpty()) {
            Map<String, Object> row = r.rows().get(0);
            for (int i = 0; i < columnNames.size(); i++) {
                long nn = toLong(getCI(row, "nn_" + i));
                long nulls = total - nn;
                cols.add(new NullRatio.ColumnEntry(columnNames.get(i), nn, nulls,
                        ratio(nulls, total), total > 0 && nulls * 2 > total));
            }
        }
        cols.sort((a, b) -> Double.compare(b.nullRatio(), a.nullRatio()));

        return new NullRatio(effectiveSchema, table, total, cols);
    }

    // ---------------- estimateSelectivity ----------------

    /**
     * Runs {@code EXPLAIN} on {@code SELECT 1 FROM <table> WHERE <predicate>} and reports the
     * planner's row estimate, without actually executing the query. A second {@code EXPLAIN}
     * without the predicate gives the baseline so the caller can see the selectivity ratio.
     *
     * <p>Useful when comparing two candidate predicates - the one with the lower estimated
     * cardinality is more selective and should usually be tried first.
     */
    public SelectivityEstimate estimateSelectivity(String schema, String table, String predicate)
            throws SQLException {
        requireIdent("table", table);
        if (predicate == null || predicate.isBlank()) {
            throw new IllegalArgumentException("predicate must be provided");
        }
        if (predicate.contains(";")) {
            throw new IllegalArgumentException("predicate must be a single boolean expression (no ';' allowed)");
        }
        String effectiveSchema = resolveSchema(schema);
        String qTable = qualify(schema, table);

        String filteredSql = "SELECT 1 FROM " + qTable + " WHERE (" + predicate + ")";
        String baselineSql = "SELECT 1 FROM " + qTable;

        Long filtered = explainRootRows(filteredSql);
        Long baseline = explainRootRows(baselineSql);

        Double selectivity = null;
        if (filtered != null && baseline != null && baseline > 0) {
            selectivity = ratio(filtered, baseline);
        }

        return new SelectivityEstimate(effectiveSchema, table, predicate,
                filtered, baseline, selectivity,
                "Estimates come from the query planner and can be off if statistics are stale. " +
                        "Run ANALYZE (PostgreSQL) or DBMS_STATS.GATHER_TABLE_STATS (Oracle) for fresher numbers.");
    }

    // ---------------- joinCardinality ----------------

    /**
     * Estimates the row count of {@code fromTable JOIN toTable ON left.col = right.col}
     * via an EXPLAIN on {@code SELECT 1 FROM ...}. The query itself is not executed.
     *
     * <p>Parameter order encodes JOIN direction: the {@code from*} side is the driving table
     * (matters for {@code LEFT} / {@code RIGHT} joins). Supported join types: {@code INNER}
     * (default), {@code LEFT}, {@code RIGHT}, {@code FULL}.
     */
    public JoinCardinality joinCardinality(String fromSchema, String fromTable, String leftColumn,
                                                String toSchema, String toTable, String rightColumn,
                                                String joinType) throws SQLException {
        requireIdent("fromTable", fromTable);
        requireIdent("leftColumn", leftColumn);
        requireIdent("toTable", toTable);
        requireIdent("rightColumn", rightColumn);
        String jt = (joinType == null || joinType.isBlank()) ? "INNER" : joinType.trim().toUpperCase(Locale.ROOT);
        if (!jt.equals("INNER") && !jt.equals("LEFT") && !jt.equals("RIGHT") && !jt.equals("FULL")) {
            throw new IllegalArgumentException("joinType must be one of INNER, LEFT, RIGHT, FULL");
        }

        String lTable = qualify(fromSchema, fromTable);
        String rTable = qualify(toSchema, toTable);
        String lCol = quoteIdent(leftColumn);
        String rCol = quoteIdent(rightColumn);
        String joinKeyword = jt.equals("INNER") ? "JOIN" : jt + " JOIN";

        String sql = "SELECT 1 FROM " + lTable + " L " + joinKeyword + " " + rTable + " R " +
                "ON L." + lCol + " = R." + rCol;

        Long estimated = explainRootRows(sql);
        Long lBase = explainRootRows("SELECT 1 FROM " + lTable);
        Long rBase = explainRootRows("SELECT 1 FROM " + rTable);

        Long cartesian = null;
        Double selectivityVsCartesian = null;
        if (estimated != null && lBase != null && rBase != null) {
            cartesian = safeMultiply(lBase, rBase);
            if (cartesian > 0) {
                selectivityVsCartesian = ratio(estimated, cartesian);
            }
        }

        return new JoinCardinality(resolveSchema(fromSchema), fromTable, leftColumn, lBase,
                resolveSchema(toSchema), toTable, rightColumn, rBase,
                jt, estimated, cartesian, selectivityVsCartesian,
                "Estimate from the query planner; actual rows may differ if statistics are stale.");
    }

    // ---------------- internal helpers ----------------

    /**
     * Runs a structured EXPLAIN on {@code sql} and returns the root node's estimated row count.
     * Handles the two-step Oracle flow (populate {@code PLAN_TABLE}, then read back) and the
     * single-step PostgreSQL flow (JSON EXPLAIN is its own query).
     */
    private Long explainRootRows(String sql) throws SQLException {
        if (dialect.kind() == DatabaseKind.MSSQL) {
            ParsedPlan parsed = executor.withConnection(conn -> {
                runStatement(conn, "SET SHOWPLAN_XML ON");
                try {
                    QueryResult planRows = queryNoParamsWithStatement(conn, sql);
                    return planParser.parse(planRows, false);
                } finally {
                    runStatement(conn, "SET SHOWPLAN_XML OFF");
                }
            });
            PlanNode root = parsed.root();
            return root == null ? null : root.estimatedRows();
        }
        String statementId = newExplainStatementId();
        String explainSql = dialect.buildStructuredExplain(sql, false, statementId);
        String displaySql = dialect.structuredPlanQuery(statementId);
        ParsedPlan parsed = executor.withConnection(conn -> {
            QueryResult planRows;
            if (displaySql != null) {
                runUpdate(conn, explainSql);
                planRows = queryWithParams(conn, displaySql, List.of(statementId));
            } else {
                planRows = queryNoParams(conn, explainSql);
            }
            return planParser.parse(planRows, false);
        }, displaySql == null);
        PlanNode root = parsed.root();
        return root == null ? null : root.estimatedRows();
    }

    private String newExplainStatementId() {
        int random = ThreadLocalRandom.current().nextInt(36 * 36 * 36);
        return "JDBC_MCP_" + Long.toString(System.nanoTime(), 36) + "_"
                + Integer.toString(random, 36);
    }

    private QueryResult queryNoParams(Connection conn, String sql) throws SQLException {
        return queryWithParams(conn, sql, List.of());
    }

    private QueryResult queryNoParamsWithStatement(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (properties.queryTimeoutSeconds() > 0) st.setQueryTimeout(properties.queryTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(sql)) {
                return readAll(rs);
            }
        }
    }

    private QueryResult queryWithParams(Connection conn, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (properties.queryTimeoutSeconds() > 0) ps.setQueryTimeout(properties.queryTimeoutSeconds());
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return readAll(rs);
            }
        }
    }

    private void runUpdate(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (properties.queryTimeoutSeconds() > 0) ps.setQueryTimeout(properties.queryTimeoutSeconds());
            ps.execute();
        }
    }

    private void runStatement(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (properties.queryTimeoutSeconds() > 0) st.setQueryTimeout(properties.queryTimeoutSeconds());
            st.execute(sql);
        }
    }

    private QueryResult readAll(ResultSet rs) throws SQLException {
        var md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>(n);
        List<String> types = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            cols.add(md.getColumnLabel(i));
            types.add(md.getColumnTypeName(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= n; i++) row.put(cols.get(i - 1), rs.getObject(i));
            rows.add(row);
        }
        return new QueryResult(cols, types, rows, false, rows.size());
    }

    private long fetchTotalRows(String qualifiedTable) throws SQLException {
        QueryResult r = executor.queryInternal("SELECT COUNT(*) AS total FROM " + qualifiedTable,
                Collections.emptyList(), 1);
        if (r.rows().isEmpty()) return 0L;
        return toLong(getCI(r.rows().get(0), "total"));
    }

    private List<String> fetchColumnNames(String schema, String table) throws SQLException {
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            List<String> out = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, schema, table, "%")) {
                List<Object[]> ordered = new ArrayList<>();
                while (rs.next()) {
                    ordered.add(new Object[]{rs.getInt("ORDINAL_POSITION"), rs.getString("COLUMN_NAME")});
                }
                ordered.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
                for (Object[] r : ordered) out.add((String) r[1]);
            }
            return out;
        });
    }

    private ColumnType fetchColumnType(String schema, String table, String column) throws SQLException {
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getColumns(null, schema, table, column)) {
                if (rs.next()) {
                    int jdbcType = rs.getInt("DATA_TYPE");
                    String typeName = rs.getString("TYPE_NAME");
                    return new ColumnType(typeName, isNumericJdbcType(jdbcType));
                }
            }
            // Fallback: try case-insensitive match on column name (some drivers uppercase).
            try (ResultSet rs = md.getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    if (name != null && name.equalsIgnoreCase(column)) {
                        int jdbcType = rs.getInt("DATA_TYPE");
                        String typeName = rs.getString("TYPE_NAME");
                        return new ColumnType(typeName, isNumericJdbcType(jdbcType));
                    }
                }
            }
            return new ColumnType(null, false);
        });
    }

    private static boolean isNumericJdbcType(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> true;
            default -> false;
        };
    }

    private String qualify(String schema, String table) {
        String s = (schema == null || schema.isBlank()) ? null : schema;
        if (s == null) return quoteIdent(table);
        return quoteIdent(s) + "." + quoteIdent(table);
    }

    private String quoteIdent(String id) {
        requireIdent("identifier", id);
        if (dialect.kind() == DatabaseKind.ORACLE) {
            // Oracle stores unquoted identifiers in upper case - pass as-is so existing
            // tables resolve without quoting.
            return id;
        }
        if (dialect.kind() == DatabaseKind.MSSQL) {
            return "[" + id + "]";
        }
        return "\"" + id + "\"";
    }

    private static void requireIdent(String paramName, String value) {
        if (value == null || value.isBlank() || !SIMPLE_IDENT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Illegal " + paramName + ": '" + value + "'. " +
                            "Only letters, digits and _ $ # are allowed (must start with a letter or _).");
        }
    }

    private String resolveSchema(String schema) throws SQLException {
        return schemaResolver.resolve(schema);
    }

    private static Double ratio(long num, long denom) {
        if (denom <= 0) return null;
        double r = (double) num / (double) denom;
        // Round to 6 digits for stable JSON output.
        return Math.round(r * 1_000_000.0) / 1_000_000.0;
    }

    private static long safeMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
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

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            try {
                return Math.round(Double.parseDouble(v.toString().trim()));
            } catch (NumberFormatException e2) {
                return 0L;
            }
        }
    }

    private record ColumnType(String typeName, boolean numeric) {}
}
