package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Audit of child-side foreign keys that lack supporting indexes.")
public record FkIndexCoverage(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true)
        String table,
        @Schema(description = "Number of tables inspected by the tool before caps were applied.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tablesScanned,
        @Schema(description = "Total number of foreign keys inspected for index coverage.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int foreignKeysTotal,
        @Schema(description = "Number of foreign keys without a supporting child-side index.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int uncoveredCount,
        @Schema(description = "Foreign keys that lack a supporting child-side index.", nullable = true)
        List<UncoveredEntry> uncovered
) {
    @Schema(description = "One foreign key without a supporting child-side index, with suggested index columns.")
    public record UncoveredEntry(
            @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
            String schema,
            @Schema(description = "Table name for a finding or statistics row.", nullable = true)
            String tableName,
            @Schema(description = "Foreign-key constraint name for declared schema edges, when available.", nullable = true)
            String fkName,
            @Schema(description = "Child-side foreign-key columns that should be indexed together.", nullable = true)
            List<String> fkColumns,
            @Schema(description = "Schema of the table referenced by a foreign key or constraint.", nullable = true)
            String referencedSchema,
            @Schema(description = "Table referenced by a foreign key or constraint.", nullable = true)
            String referencedTable,
            @Schema(description = "Columns referenced by the foreign key or constraint, in key order.", nullable = true)
            List<String> referencedColumns,
            @Schema(description = "Suggested index column order to support the foreign key.", nullable = true)
            List<String> suggestedIndexColumns
    ) {}
}
