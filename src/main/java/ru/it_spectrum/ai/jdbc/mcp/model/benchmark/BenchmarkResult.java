package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Wall-clock benchmark result for a read-only query, including cold and warm run timing and the size of the last result set.")
public record BenchmarkResult(
        @Schema(description = "Database engine that produced the result, such as PostgreSQL, Oracle, or SQL Server.", nullable = true)
        String engine,
        @Schema(description = "Number of measured runs included in these timing statistics.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int runs,
        @Schema(description = "Number of cold runs executed before warm measurements.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int coldRuns,
        @Schema(description = "Number of warm runs used for the primary benchmark statistics.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int warmRuns,
        @Schema(description = "Row limit applied to the query or page size requested by the caller.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int limit,
        @Schema(description = "Per-statement timeout applied during execution, in seconds.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int timeoutSeconds,
        @Schema(description = "Timing statistics for cold benchmark runs in milliseconds.", nullable = true)
        TimingStats coldMs,
        @Schema(description = "Timing statistics for warm benchmark runs in milliseconds.", nullable = true)
        TimingStats warmMs,
        @Schema(description = "Individual elapsed times for all benchmark executions, in milliseconds.", nullable = true)
        List<Double> allMs,
        @Schema(description = "Row, column, and truncation information for the last benchmark result set.", nullable = true)
        ResultSize resultSize,
        @Schema(description = "Additional context about support, limits, interpretation, or engine-specific behavior.", nullable = true)
        String note
) {
}