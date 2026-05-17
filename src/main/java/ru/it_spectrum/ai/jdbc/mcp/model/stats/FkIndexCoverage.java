package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "FkIndexCoverage response payload.")
public record FkIndexCoverage(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Tables Scanned.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tablesScanned,
        @Schema(description = "Foreign Keys Total.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int foreignKeysTotal,
        @Schema(description = "Uncovered Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int uncoveredCount,
        @Schema(description = "Uncovered.", nullable = true)
        List<UncoveredEntry> uncovered
) {
    @Schema(description = "UncoveredEntry response payload.")
    public record UncoveredEntry(
            @Schema(description = "Schema.", nullable = true)
            String schema,
            @Schema(description = "Table Name.", nullable = true)
            String tableName,
            @Schema(description = "Fk Name.", nullable = true)
            String fkName,
            @Schema(description = "Fk Columns.", nullable = true)
            List<String> fkColumns,
            @Schema(description = "Referenced Schema.", nullable = true)
            String referencedSchema,
            @Schema(description = "Referenced Table.", nullable = true)
            String referencedTable,
            @Schema(description = "Referenced Columns.", nullable = true)
            List<String> referencedColumns,
            @Schema(description = "Suggested Index Columns.", nullable = true)
            List<String> suggestedIndexColumns
    ) {}
}
