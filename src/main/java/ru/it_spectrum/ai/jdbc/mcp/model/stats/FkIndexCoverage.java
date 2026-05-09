package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import java.util.List;

public record FkIndexCoverage(
        String schema,
        String table,
        int tablesScanned,
        int foreignKeysTotal,
        int uncoveredCount,
        List<UncoveredEntry> uncovered
) {
    public record UncoveredEntry(
            String schema,
            String tableName,
            String fkName,
            List<String> fkColumns,
            String referencedSchema,
            String referencedTable,
            List<String> referencedColumns,
            List<String> suggestedIndexColumns
    ) {}
}
