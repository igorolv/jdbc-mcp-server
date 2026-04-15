package ru.it_spectrum.ai.jdbc.mcp.sql;

import java.util.List;
import java.util.Map;

/**
 * Flat row-set returned by {@link SqlExecutor}. {@code columns} preserves the column order
 * from the driver; {@code rows} is a list of column-name → value maps (preserving insertion order).
 *
 * @param columns    ordered column names as reported by {@code ResultSetMetaData}
 * @param columnTypes ordered JDBC type names per column (best-effort, for display)
 * @param rows       row data, one map per row
 * @param truncated  {@code true} if the server-side result contained more rows than {@code maxRows}
 * @param rowCount   number of rows actually returned (same as {@code rows.size()}, for convenience)
 */
public record QueryResult(
        List<String> columns,
        List<String> columnTypes,
        List<Map<String, Object>> rows,
        boolean truncated,
        int rowCount
) {
}
