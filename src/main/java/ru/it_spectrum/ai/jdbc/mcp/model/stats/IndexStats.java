package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "IndexStats response payload.")
public record IndexStats(
        @Schema(description = "Indexes.", nullable = true)
        List<IndexStatsRow> indexes
) {
    @Schema(description = "IndexStatsRow response payload.")
    public record IndexStatsRow(
            @Schema(description = "Schema.", nullable = true)
            String schema,
            @Schema(description = "Table.", nullable = true)
            String table,
            @Schema(description = "Index Name.", nullable = true)
            String indexName,
            @Schema(description = "Index Type.", nullable = true)
            String indexType,
            @Schema(description = "Is Unique.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean isUnique,
            @Schema(description = "Is Primary.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean isPrimary,
            @Schema(description = "Columns.", nullable = true)
            List<String> columns,
            @Schema(description = "Size Bytes.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long sizeBytes,
            @Schema(description = "Idx Scans.", nullable = true)
            Long idxScans,
            @Schema(description = "Idx Tup Read.", nullable = true)
            Long idxTupRead,
            @Schema(description = "Idx Tup Fetch.", nullable = true)
            Long idxTupFetch,
            @Schema(description = "Distinct Keys.", nullable = true)
            Long distinctKeys,
            @Schema(description = "Clustering Factor.", nullable = true)
            Long clusteringFactor,
            @Schema(description = "Blevel.", nullable = true)
            Integer blevel,
            @Schema(description = "Leaf Blocks.", nullable = true)
            Long leafBlocks,
            @Schema(description = "Last Analyzed.", nullable = true)
            String lastAnalyzed
    ) {
    }
}