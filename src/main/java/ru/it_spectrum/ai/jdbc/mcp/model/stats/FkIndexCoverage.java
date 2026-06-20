package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Audit of child-side foreign keys that lack supporting indexes.")
public record FkIndexCoverage(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Number of tables inspected by the tool before caps were applied.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tablesScanned,
        @Schema(description = "Total number of foreign keys inspected for index coverage.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int foreignKeysTotal,
        @Schema(description = "Number of foreign keys without a supporting child-side index.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int uncoveredCount,
        @Schema(description = "Foreign keys that lack a supporting child-side index.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<UncoveredEntry> uncovered
) {
    @Schema(description = "One foreign key without a supporting child-side index, with suggested index columns.")
    public record UncoveredEntry(
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String schema,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String tableName,
            @Schema(description = "Foreign-key constraint name for declared schema edges, when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String fkName,
            @Schema(description = "Child-side foreign-key columns that should be indexed together.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            List<String> fkColumns,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String referencedSchema,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String referencedTable,
            @Schema(description = "Columns referenced by the foreign key or constraint, in key order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            List<String> referencedColumns,
            @Schema(description = "Suggested index column order to support the foreign key.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            List<String> suggestedIndexColumns
    ) {}
}
