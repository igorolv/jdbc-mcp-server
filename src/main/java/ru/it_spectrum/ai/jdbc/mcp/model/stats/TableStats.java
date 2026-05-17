package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Engine-specific table size, row estimate, activity, maintenance, and storage statistics.")
public record TableStats(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true)
        String table,
        @Schema(description = "True when the requested object or path was found.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean found,
        @Schema(description = "Engine-specific relation kind for the table or view.", nullable = true)
        Object relkind,
        @Schema(description = "Planner or catalog estimate of rows for this object or operation.", nullable = true)
        Object estimatedRows,
        @Schema(description = "Total storage used by the table, indexes, and auxiliary storage, in bytes.", nullable = true)
        Object totalSizeBytes,
        @Schema(description = "Storage used by the table heap or base data, in bytes.", nullable = true)
        Object tableSizeBytes,
        @Schema(description = "Storage used by indexes on the table, in bytes.", nullable = true)
        Object indexesSizeBytes,
        @Schema(description = "PostgreSQL TOAST storage used by the table, in bytes.", nullable = true)
        Object toastSizeBytes,
        @Schema(description = "Estimated number of live tuples or rows.", nullable = true)
        Object liveTuples,
        @Schema(description = "Estimated number of dead tuples or obsolete row versions.", nullable = true)
        Object deadTuples,
        @Schema(description = "Approximate percentage of dead tuples among total tuples.", nullable = true)
        Object deadTuplePct,
        @Schema(description = "Number of sequential scans recorded for the table.", nullable = true)
        Object seqScan,
        @Schema(description = "Number of tuples read by sequential scans.", nullable = true)
        Object seqTupRead,
        @Schema(description = "Number of index scans recorded for the table.", nullable = true)
        Object idxScan,
        @Schema(description = "Number of table tuples fetched through this index, when available.", nullable = true)
        Object idxTupFetch,
        @Schema(description = "Number of inserted tuples recorded by engine statistics.", nullable = true)
        Object tupIns,
        @Schema(description = "Number of updated tuples recorded by engine statistics.", nullable = true)
        Object tupUpd,
        @Schema(description = "Number of deleted tuples recorded by engine statistics.", nullable = true)
        Object tupDel,
        @Schema(description = "Timestamp of the last manual vacuum, when available.", nullable = true)
        Object lastVacuum,
        @Schema(description = "Timestamp of the last automatic vacuum, when available.", nullable = true)
        Object lastAutovacuum,
        @Schema(description = "Timestamp of the last manual analyze/statistics collection, when available.", nullable = true)
        Object lastAnalyze,
        @Schema(description = "Timestamp of the last automatic analyze/statistics collection, when available.", nullable = true)
        Object lastAutoanalyze,
        @Schema(description = "Engine-reported allocated block count, when available.", nullable = true)
        Object blocks,
        @Schema(description = "Engine-reported empty block count, when available.", nullable = true)
        Object emptyBlocks,
        @Schema(description = "Average row length reported by optimizer statistics, when available.", nullable = true)
        Object avgRowLen,
        @Schema(description = "Oracle chained row count, when available.", nullable = true)
        Object chainCount,
        @Schema(description = "Timestamp when database optimizer statistics were last collected, when available.", nullable = true)
        Object lastAnalyzed,
        @Schema(description = "Sample size used for optimizer statistics, when available.", nullable = true)
        Object sampleSize,
        @Schema(description = "Whether the table is partitioned, when the engine reports it.", nullable = true)
        Object partitioned,
        @Schema(description = "Whether the table is temporary, when the engine reports it.", nullable = true)
        Object temporary,
        @Schema(description = "Oracle flag indicating whether global statistics are present.", nullable = true)
        Object globalStats,
        @Schema(description = "Oracle flag indicating whether user-defined statistics are present.", nullable = true)
        Object userStats,
        @Schema(description = "Compression setting reported for the table, when available.", nullable = true)
        Object compression,
        @Schema(description = "SQL Server object creation timestamp, when available.", nullable = true)
        Object createDate,
        @Schema(description = "SQL Server object modification timestamp, when available.", nullable = true)
        Object modifyDate,
        @Schema(description = "SQL Server flag indicating a memory-optimized table.", nullable = true)
        Object isMemoryOptimized,
        @Schema(description = "SQL Server temporal table type description.", nullable = true)
        Object temporalTypeDesc,
        @Schema(description = "SQL Server flag indicating a FileTable.", nullable = true)
        Object isFiletable,
        @Schema(description = "Oracle segment size in bytes, when segment metadata is accessible.", nullable = true)
        Object segmentBytes,
        @Schema(description = "Permission or lookup error encountered while reading segment size.", nullable = true)
        String segmentBytesError,
        @Schema(description = "Error encountered while loading table statistics; other fields may be partial when set.", nullable = true)
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