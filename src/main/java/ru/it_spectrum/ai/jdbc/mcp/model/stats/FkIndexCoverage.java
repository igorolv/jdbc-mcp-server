package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import ru.it_spectrum.ai.jdbc.mcp.model.JsonKey;

import java.util.List;

public record FkIndexCoverage(
        String schema,
        String table,
        @JsonKey("tables_scanned") int tablesScanned,
        @JsonKey("foreign_keys_total") int foreignKeysTotal,
        @JsonKey("uncovered_count") int uncoveredCount,
        List<UncoveredEntry> uncovered
) {
    public record UncoveredEntry(
            String schema,
            @JsonKey("table") String tableName,
            @JsonKey("fk_name") String fkName,
            @JsonKey("fk_columns") List<String> fkColumns,
            @JsonKey("referenced_schema") String referencedSchema,
            @JsonKey("referenced_table") String referencedTable,
            @JsonKey("referenced_columns") List<String> referencedColumns,
            @JsonKey("suggested_index_columns") List<String> suggestedIndexColumns
    ) {}
}
