package ru.it_spectrum.ai.jdbc.mcp.model.query;

public record CheckedTableSummary(
        String schema,
        String table,
        boolean exists,
        int columnCount,
        int indexCount
) {
}
