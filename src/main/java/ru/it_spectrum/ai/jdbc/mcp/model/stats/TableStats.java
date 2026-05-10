package ru.it_spectrum.ai.jdbc.mcp.model.stats;

public record TableStats(
        String schema,
        String table,
        boolean found,
        Object relkind,
        Object estimatedRows,
        Object totalSizeBytes,
        Object tableSizeBytes,
        Object indexesSizeBytes,
        Object toastSizeBytes,
        Object liveTuples,
        Object deadTuples,
        Object deadTuplePct,
        Object seqScan,
        Object seqTupRead,
        Object idxScan,
        Object idxTupFetch,
        Object tupIns,
        Object tupUpd,
        Object tupDel,
        Object lastVacuum,
        Object lastAutovacuum,
        Object lastAnalyze,
        Object lastAutoanalyze,
        Object blocks,
        Object emptyBlocks,
        Object avgRowLen,
        Object chainCount,
        Object lastAnalyzed,
        Object sampleSize,
        Object partitioned,
        Object temporary,
        Object globalStats,
        Object userStats,
        Object compression,
        Object createDate,
        Object modifyDate,
        Object isMemoryOptimized,
        Object temporalTypeDesc,
        Object isFiletable,
        Object segmentBytes,
        String segmentBytesError,
        String error
) {
    public static TableStats notFound(String schema, String table) {
        return new TableStats(
                schema, table, false,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public static TableStats error(String schema, String table, String error) {
        return new TableStats(
                schema, table, false,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, error);
    }
}