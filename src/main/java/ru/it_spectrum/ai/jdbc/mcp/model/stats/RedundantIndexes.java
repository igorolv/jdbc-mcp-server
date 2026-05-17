package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Indexes that appear redundant because their leading columns are covered by another index on the same table.")
public record RedundantIndexes(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Number of redundant-index findings returned.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count,
        @Schema(description = "Schema lint or redundant-index findings.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Finding> findings
) {
    @Schema(description = "One redundant-index finding and the larger index that covers it.")
    public record Finding(
            @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String schema,
            @Schema(description = "Table name for a finding or statistics row.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String tableName,
            @Schema(description = "Index that appears redundant because another index has the same leading columns.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String shadowedIndex,
            @Schema(description = "Column list of the potentially redundant index.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String shadowedColumns,
            @Schema(description = "Size in bytes of the potentially redundant index.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long shadowedSizeBytes,
            @Schema(description = "Index that covers the shadowed index by leading-column prefix.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String coveredByIndex,
            @Schema(description = "Column list of the covering index.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String coveredByColumns,
            @Schema(description = "Engine-specific index type or access method.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String indexType
    ) {}
}
