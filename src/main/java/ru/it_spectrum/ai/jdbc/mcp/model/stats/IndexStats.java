package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import java.util.List;

public record IndexStats(
        List<IndexStatsRow> indexes
) {
    public record IndexStatsRow(
            String schema,
            String table,
            String indexName,
            String indexType,
            boolean isUnique,
            boolean isPrimary,
            List<String> columns,
            long sizeBytes,
            Long idxScans,
            Long idxTupRead,
            Long idxTupFetch,
            Long distinctKeys,
            Long clusteringFactor,
            Integer blevel,
            Long leafBlocks,
            String lastAnalyzed
    ) {
    }
}