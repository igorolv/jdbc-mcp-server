package ru.it_spectrum.ai.jdbc.mcp.dialect;

/**
 * {@code columnHistogram} for engines with window functions but no ordered-set aggregates
 * (Firebird 3, SQLite): one pass, {@code ROW_NUMBER} over the non-null values, and each percentile
 * {@code p} is the smallest value whose rank reaches {@code p * n} — the definition of
 * {@code percentile_disc}. Comparing the integer rank with {@code p * n} directly avoids
 * {@code CEILING}, which not every engine has.
 */
final class RankPercentiles {

    private static final String[][] PERCENTILES = {{"0.25", "p25"}, {"0.5", "p50"}, {"0.75", "p75"},
            {"0.9", "p90"}, {"0.95", "p95"}, {"0.99", "p99"}};

    private RankPercentiles() {
    }

    static String histogramQuery(String qualifiedTable, String quotedColumn) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS total_rows,
                       COUNT(v) AS non_null_rows,
                       MIN(v) AS min_value,
                       MAX(v) AS max_value""");
        for (String[] p : PERCENTILES) {
            sql.append(",\n       MIN(CASE WHEN rn >= ").append(p[0])
                    .append(" * cnt THEN v END) AS ").append(p[1]);
        }
        sql.append("""

                FROM (
                    SELECT v,
                           CASE WHEN v IS NULL THEN NULL
                                ELSE ROW_NUMBER() OVER (
                                    PARTITION BY CASE WHEN v IS NULL THEN 1 ELSE 0 END ORDER BY v)
                           END AS rn,
                           COUNT(v) OVER () AS cnt
                    FROM (SELECT %s AS v FROM %s) b
                ) r""".formatted(quotedColumn, qualifiedTable));
        return sql.toString();
    }
}
