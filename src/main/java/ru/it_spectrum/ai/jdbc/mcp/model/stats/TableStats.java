package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "TableStats response payload.")
public record TableStats(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Found.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean found,
        @Schema(description = "Relkind.", nullable = true)
        Object relkind,
        @Schema(description = "Estimated Rows.", nullable = true)
        Object estimatedRows,
        @Schema(description = "Total Size Bytes.", nullable = true)
        Object totalSizeBytes,
        @Schema(description = "Table Size Bytes.", nullable = true)
        Object tableSizeBytes,
        @Schema(description = "Indexes Size Bytes.", nullable = true)
        Object indexesSizeBytes,
        @Schema(description = "Toast Size Bytes.", nullable = true)
        Object toastSizeBytes,
        @Schema(description = "Live Tuples.", nullable = true)
        Object liveTuples,
        @Schema(description = "Dead Tuples.", nullable = true)
        Object deadTuples,
        @Schema(description = "Dead Tuple Pct.", nullable = true)
        Object deadTuplePct,
        @Schema(description = "Seq Scan.", nullable = true)
        Object seqScan,
        @Schema(description = "Seq Tup Read.", nullable = true)
        Object seqTupRead,
        @Schema(description = "Idx Scan.", nullable = true)
        Object idxScan,
        @Schema(description = "Idx Tup Fetch.", nullable = true)
        Object idxTupFetch,
        @Schema(description = "Tup Ins.", nullable = true)
        Object tupIns,
        @Schema(description = "Tup Upd.", nullable = true)
        Object tupUpd,
        @Schema(description = "Tup Del.", nullable = true)
        Object tupDel,
        @Schema(description = "Last Vacuum.", nullable = true)
        Object lastVacuum,
        @Schema(description = "Last Autovacuum.", nullable = true)
        Object lastAutovacuum,
        @Schema(description = "Last Analyze.", nullable = true)
        Object lastAnalyze,
        @Schema(description = "Last Autoanalyze.", nullable = true)
        Object lastAutoanalyze,
        @Schema(description = "Blocks.", nullable = true)
        Object blocks,
        @Schema(description = "Empty Blocks.", nullable = true)
        Object emptyBlocks,
        @Schema(description = "Avg Row Len.", nullable = true)
        Object avgRowLen,
        @Schema(description = "Chain Count.", nullable = true)
        Object chainCount,
        @Schema(description = "Last Analyzed.", nullable = true)
        Object lastAnalyzed,
        @Schema(description = "Sample Size.", nullable = true)
        Object sampleSize,
        @Schema(description = "Partitioned.", nullable = true)
        Object partitioned,
        @Schema(description = "Temporary.", nullable = true)
        Object temporary,
        @Schema(description = "Global Stats.", nullable = true)
        Object globalStats,
        @Schema(description = "User Stats.", nullable = true)
        Object userStats,
        @Schema(description = "Compression.", nullable = true)
        Object compression,
        @Schema(description = "Create Date.", nullable = true)
        Object createDate,
        @Schema(description = "Modify Date.", nullable = true)
        Object modifyDate,
        @Schema(description = "Is Memory Optimized.", nullable = true)
        Object isMemoryOptimized,
        @Schema(description = "Temporal Type Desc.", nullable = true)
        Object temporalTypeDesc,
        @Schema(description = "Is Filetable.", nullable = true)
        Object isFiletable,
        @Schema(description = "Segment Bytes.", nullable = true)
        Object segmentBytes,
        @Schema(description = "Segment Bytes Error.", nullable = true)
        String segmentBytesError,
        @Schema(description = "Error.", nullable = true)
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