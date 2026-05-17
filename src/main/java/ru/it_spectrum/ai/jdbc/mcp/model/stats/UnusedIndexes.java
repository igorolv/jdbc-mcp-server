package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "UnusedIndexes response payload.")
public record UnusedIndexes(
        @Schema(description = "Supported.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean supported,
        @Schema(description = "Note.", nullable = true)
        String note,
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count,
        @Schema(description = "Indexes.", nullable = true)
        List<UnusedIndexEntry> indexes
) {
    @Schema(description = "UnusedIndexEntry response payload.")
    public record UnusedIndexEntry(
            @Schema(description = "Schema.", nullable = true)
            String schema,
            @Schema(description = "Table Name.", nullable = true)
            String tableName,
            @Schema(description = "Index Name.", nullable = true)
            String indexName,
            @Schema(description = "Columns.", nullable = true)
            String columns,
            @Schema(description = "Size Bytes.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long sizeBytes,
            @Schema(description = "Index Type.", nullable = true)
            String indexType
    ) {}
}
