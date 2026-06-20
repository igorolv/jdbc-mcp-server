package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "PostgreSQL unused-index audit result; other engines may return a support note instead.")
public record UnusedIndexes(
        @Schema(description = "True when this tool is supported for the current database engine and privileges.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean supported,
        @Schema(description = "Additional context about support, limits, interpretation, or engine-specific behavior.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String note,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Number of unused indexes returned.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count,
        @Schema(description = "Indexes available on the table or returned by an index-statistics scan.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<UnusedIndexEntry> indexes
) {
    @Schema(description = "One non-primary, non-unique index with zero recorded scans.")
    public record UnusedIndexEntry(
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String schema,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String tableName,
            @Schema(description = "Index name as reported by the database.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String indexName,
            @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String columns,
            @Schema(description = "On-disk size in bytes when the database exposes it.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long sizeBytes,
            @Schema(description = "Engine-specific index type or access method.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String indexType
    ) {}
}
