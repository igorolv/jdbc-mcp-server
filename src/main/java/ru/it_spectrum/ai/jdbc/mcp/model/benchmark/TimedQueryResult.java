package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import java.util.List;
import java.util.Map;

public record TimedQueryResult(
        String engine,
        double elapsed_ms,
        int row_count,
        boolean truncated,
        List<String> columns,
        List<String> column_types,
        List<Map<String, Object>> rows,
        PgStatStatements pg_stat_statements
) {
}
