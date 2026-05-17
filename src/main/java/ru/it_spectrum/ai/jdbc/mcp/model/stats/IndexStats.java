package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Index statistics for a schema or table, including size, columns, uniqueness, and engine-specific usage counters.")
public record IndexStats(
        @Schema(description = "Indexes available on the table or returned by an index-statistics scan.", nullable = true)
        List<IndexStatsRow> indexes
) {
    @Schema(description = "Statistics and metadata for one index.")
    public record IndexStatsRow(
            @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
            String schema,
            @Schema(description = "Table name within the schema.", nullable = true)
            String table,
            @Schema(description = "Index name as reported by the database.", nullable = true)
            String indexName,
            @Schema(description = "Engine-specific index type or access method.", nullable = true)
            String indexType,
            @Schema(description = "True when the index is unique.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean isUnique,
            @Schema(description = "True when the index backs a primary-key constraint.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean isPrimary,
            @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true)
            List<String> columns,
            @Schema(description = "On-disk size in bytes when the database exposes it.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long sizeBytes,
            @Schema(description = "Number of recorded index scans since statistics were last reset, when available.", nullable = true)
            Long idxScans,
            @Schema(description = "Number of index tuples read according to engine statistics, when available.", nullable = true)
            Long idxTupRead,
            @Schema(description = "Number of table tuples fetched through this index, when available.", nullable = true)
            Long idxTupFetch,
            @Schema(description = "Oracle distinct key count for the index, when available.", nullable = true)
            Long distinctKeys,
            @Schema(description = "Oracle clustering factor for the index, when available.", nullable = true)
            Long clusteringFactor,
            @Schema(description = "Oracle B-tree level for the index, when available.", nullable = true)
            Integer blevel,
            @Schema(description = "Oracle leaf block count for the index, when available.", nullable = true)
            Long leafBlocks,
            @Schema(description = "Timestamp when database optimizer statistics were last collected, when available.", nullable = true)
            String lastAnalyzed
    ) {
    }
}