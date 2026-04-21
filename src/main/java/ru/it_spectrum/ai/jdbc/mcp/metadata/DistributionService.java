package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
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
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Column-level selectivity and distribution analyses that the optimiser-flavoured
 * {@link StatsService} deliberately stays out of.
 *
 * <p>Answers "is this predicate going to filter anything?" / "how skewed is this column?" /
 * "will this join return 1 K rows or 1 B?" — information an LLM needs to choose an index
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

    /** Simple identifier pattern — same shape as {@code SampleTools.quoteIdent}. */
    private static final Pattern SIMPLE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    private final SqlExecutor executor;
    private final SqlDialect dialect;
    private final JdbcProperties properties;
    private final PlanParser planParser;

    public DistributionService(SqlExecutor executor, SqlDialect dialect,
                               JdbcProperties properties, PlanParser planParser) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
        this.planParser = planParser;
    }

    // ---------------- columnDistribution ----------------

    /**
     * Returns the top-{@code topN} most frequent values of {@code column} together with their
     * share of the total row count. Lets the caller detect heavy data skew — e.g. "70 % of
     * rows have status='OK'" — which makes a non-partial index on {@code status} nearly useless.
     */
    public Map<String, Object> columnDistribution(String schema, String table,
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

        long totalFromTop = 0L;
        List<Map<String, Object>> valueRows = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            long freq = toLong(getCI(row, "frequency"));
            totalFromTop += freq;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("value", getCI(row, "value"));
            e.put("frequency", freq);
            valueRows.add(e);
        }

        long totalRows = fetchTotalRows(qTable);
        for (Map<String, Object> e : valueRows) {
            long freq = toLong(e.get("frequency"));
            e.put("ratio", ratio(freq, totalRows));
        }

        long covered = totalFromTop;
        long other = Math.max(0L, totalRows - covered);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", resolveSchema(schema));
        out.put("table", table);
        out.put("column", column);
        out.put("top_n", n);
        out.put("total_rows", totalRows);
        out.put("top_rows", covered);
        out.put("top_ratio", ratio(covered, totalRows));
        out.put("other_rows", other);
        out.put("other_ratio", ratio(other, totalRows));
        out.put("values", valueRows);
        return out;
    }

    // ---------------- columnHistogram ----------------

    /**
     * Computes min/max plus P25/P50/P75/P90/P95/P99 for the given column.
     *
     * <p>Uses standard SQL:2003 {@code WITHIN GROUP (ORDER BY ...)} aggregates — supported by
     * both PostgreSQL and Oracle. For numeric types we use {@code percentile_cont}
     * (interpolated); for any other orderable type (dates, timestamps, text) we use
     * {@code percentile_disc}, which returns an actual existing value and tolerates non-numeric
     * sorts that {@code percentile_cont} rejects on PostgreSQL.
     */
    public Map<String, Object> columnHistogram(String schema, String table, String column) throws SQLException {
        requireIdent("table", table);
        requireIdent("column", column);
        String effectiveSchema = resolveSchema(schema);

        ColumnType type = fetchColumnType(effectiveSchema, table, column);
        String qTable = qualify(schema, table);
        String qCol   = quoteIdent(column);
        String pct = type.numeric ? "percentile_cont" : "percentile_disc";

        String sql = "SELECT COUNT(*) AS total_rows, " +
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", effectiveSchema);
        out.put("table", table);
        out.put("column", column);
        out.put("column_type", type.typeName);
        out.put("percentile_function", pct);
        if (r.rows().isEmpty()) {
            out.put("total_rows", 0L);
            out.put("non_null_rows", 0L);
            return out;
        }
        Map<String, Object> row = r.rows().get(0);
        long total   = toLong(getCI(row, "total_rows"));
        long nonNull = toLong(getCI(row, "non_null_rows"));
        out.put("total_rows", total);
        out.put("non_null_rows", nonNull);
        out.put("null_rows", total - nonNull);
        out.put("null_ratio", ratio(total - nonNull, total));
        out.put("min", getCI(row, "min_value"));
        out.put("max", getCI(row, "max_value"));
        out.put("p25", getCI(row, "p25"));
        out.put("p50", getCI(row, "p50"));
        out.put("p75", getCI(row, "p75"));
        out.put("p90", getCI(row, "p90"));
        out.put("p95", getCI(row, "p95"));
        out.put("p99", getCI(row, "p99"));
        return out;
    }

    // ---------------- nullRatio ----------------

    /**
     * One pass over the table computes the null / non-null counts for every column at once,
     * so the LLM can see which columns are sparse (and therefore candidates for a partial index)
     * without issuing N queries.
     */
    public Map<String, Object> nullRatio(String schema, String table) throws SQLException {
        requireIdent("table", table);
        String effectiveSchema = resolveSchema(schema);

        List<String> columnNames = fetchColumnNames(effectiveSchema, table);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", effectiveSchema);
        out.put("table", table);

        if (columnNames.isEmpty()) {
            out.put("total_rows", 0L);
            out.put("columns", List.of());
            return out;
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
        List<Map<String, Object>> cols = new ArrayList<>(columnNames.size());
        if (!r.rows().isEmpty()) {
            Map<String, Object> row = r.rows().get(0);
            for (int i = 0; i < columnNames.size(); i++) {
                long nn = toLong(getCI(row, "nn_" + i));
                long nulls = total - nn;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("column", columnNames.get(i));
                entry.put("non_null_rows", nn);
                entry.put("null_rows", nulls);
                entry.put("null_ratio", ratio(nulls, total));
                entry.put("sparse", total > 0 && nulls * 2 > total); // >50 % null → candidate for partial index
                cols.add(entry);
            }
        }
        // Sort by descending null_ratio so the LLM sees the sparsest columns first.
        cols.sort((a, b) -> Double.compare(
                toDouble(b.get("null_ratio")), toDouble(a.get("null_ratio"))));

        out.put("total_rows", total);
        out.put("columns", cols);
        return out;
    }

    // ---------------- estimateSelectivity ----------------

    /**
     * Runs {@code EXPLAIN} on {@code SELECT 1 FROM <table> WHERE <predicate>} and reports the
     * planner's row estimate, without actually executing the query. A second {@code EXPLAIN}
     * without the predicate gives the baseline so the caller can see the selectivity ratio.
     *
     * <p>Useful when comparing two candidate predicates — the one with the lower estimated
     * cardinality is more selective and should usually be tried first.
     */
    public Map<String, Object> estimateSelectivity(String schema, String table, String predicate)
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", effectiveSchema);
        out.put("table", table);
        out.put("predicate", predicate);
        out.put("estimated_rows", filtered);
        out.put("baseline_rows", baseline);
        if (filtered != null && baseline != null && baseline > 0) {
            out.put("selectivity", ratio(filtered, baseline));
        } else {
            out.put("selectivity", null);
        }
        out.put("note", "Estimates come from the query planner and can be off if statistics are stale. " +
                "Run ANALYZE (PostgreSQL) or DBMS_STATS.GATHER_TABLE_STATS (Oracle) for fresher numbers.");
        return out;
    }

    // ---------------- joinCardinality ----------------

    /**
     * Estimates the row count of {@code leftTable JOIN rightTable ON left.col = right.col}
     * via an EXPLAIN on {@code SELECT 1 FROM ...}. The query itself is not executed.
     *
     * <p>Supported join types: {@code INNER} (default), {@code LEFT}, {@code RIGHT}, {@code FULL}.
     */
    public Map<String, Object> joinCardinality(String leftSchema, String leftTable, String leftColumn,
                                               String rightSchema, String rightTable, String rightColumn,
                                               String joinType) throws SQLException {
        requireIdent("leftTable", leftTable);
        requireIdent("leftColumn", leftColumn);
        requireIdent("rightTable", rightTable);
        requireIdent("rightColumn", rightColumn);
        String jt = (joinType == null || joinType.isBlank()) ? "INNER" : joinType.trim().toUpperCase(Locale.ROOT);
        if (!jt.equals("INNER") && !jt.equals("LEFT") && !jt.equals("RIGHT") && !jt.equals("FULL")) {
            throw new IllegalArgumentException("joinType must be one of INNER, LEFT, RIGHT, FULL");
        }

        String lTable = qualify(leftSchema, leftTable);
        String rTable = qualify(rightSchema, rightTable);
        String lCol = quoteIdent(leftColumn);
        String rCol = quoteIdent(rightColumn);
        String joinKeyword = jt.equals("INNER") ? "JOIN" : jt + " JOIN";

        String sql = "SELECT 1 FROM " + lTable + " L " + joinKeyword + " " + rTable + " R " +
                "ON L." + lCol + " = R." + rCol;

        Long estimated = explainRootRows(sql);
        Long lBase = explainRootRows("SELECT 1 FROM " + lTable);
        Long rBase = explainRootRows("SELECT 1 FROM " + rTable);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("left_schema", resolveSchema(leftSchema));
        out.put("left_table", leftTable);
        out.put("left_column", leftColumn);
        out.put("left_row_estimate", lBase);
        out.put("right_schema", resolveSchema(rightSchema));
        out.put("right_table", rightTable);
        out.put("right_column", rightColumn);
        out.put("right_row_estimate", rBase);
        out.put("join_type", jt);
        out.put("estimated_rows", estimated);
        if (estimated != null && lBase != null && rBase != null) {
            long cartesian = safeMultiply(lBase, rBase);
            out.put("cartesian_rows", cartesian);
            if (cartesian > 0) {
                out.put("selectivity_vs_cartesian", ratio(estimated, cartesian));
            }
        }
        out.put("note", "Estimate from the query planner; actual rows may differ if statistics are stale.");
        return out;
    }

    // ---------------- internal helpers ----------------

    /**
     * Runs a structured EXPLAIN on {@code sql} and returns the root node's estimated row count.
     * Handles the two-step Oracle flow (populate {@code PLAN_TABLE}, then read back) and the
     * single-step PostgreSQL flow (JSON EXPLAIN is its own query).
     */
    private Long explainRootRows(String sql) throws SQLException {
        String explainSql = dialect.buildStructuredExplain(sql, false);
        String displaySql = dialect.structuredPlanQuery();
        ParsedPlan parsed = executor.withConnection(conn -> {
            QueryResult planRows;
            if (displaySql != null) {
                runUpdate(conn, explainSql);
                planRows = queryNoParams(conn, displaySql);
            } else {
                planRows = queryNoParams(conn, explainSql);
            }
            return planParser.parse(planRows, false);
        }, displaySql == null);
        PlanNode root = parsed.root();
        return root == null ? null : root.estimatedRows();
    }

    private QueryResult queryNoParams(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (properties.queryTimeoutSeconds() > 0) ps.setQueryTimeout(properties.queryTimeoutSeconds());
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
            // Oracle stores unquoted identifiers in upper case — pass as-is so existing
            // tables resolve without quoting.
            return id;
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
        if (schema != null && !schema.isBlank()) return schema;
        if (properties.defaultSchema() != null && !properties.defaultSchema().isBlank()) {
            return properties.defaultSchema();
        }
        return executor.withConnection(dialect::fallbackSchema);
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

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private record ColumnType(String typeName, boolean numeric) {}
}
