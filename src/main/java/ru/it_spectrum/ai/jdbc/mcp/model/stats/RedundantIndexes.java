package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "RedundantIndexes response payload.")
public record RedundantIndexes(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count,
        @Schema(description = "Findings.", nullable = true)
        List<Finding> findings
) {
    @Schema(description = "Finding response payload.")
    public record Finding(
            @Schema(description = "Schema.", nullable = true)
            String schema,
            @Schema(description = "Table Name.", nullable = true)
            String tableName,
            @Schema(description = "Shadowed Index.", nullable = true)
            String shadowedIndex,
            @Schema(description = "Shadowed Columns.", nullable = true)
            String shadowedColumns,
            @Schema(description = "Shadowed Size Bytes.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long shadowedSizeBytes,
            @Schema(description = "Covered By Index.", nullable = true)
            String coveredByIndex,
            @Schema(description = "Covered By Columns.", nullable = true)
            String coveredByColumns,
            @Schema(description = "Index Type.", nullable = true)
            String indexType
    ) {}
}
