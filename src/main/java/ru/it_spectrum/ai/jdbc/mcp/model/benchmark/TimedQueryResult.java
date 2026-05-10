package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import java.util.List;
import java.util.Map;

public record TimedQueryResult(
        String engine,
        double elapsedMs,
        int rowCount,
        boolean truncated,
        List<String> columns,
        List<String> columnTypes,
        List<Map<String, Object>> rows,
        PgStatStatements pgStatStatements
) {
}