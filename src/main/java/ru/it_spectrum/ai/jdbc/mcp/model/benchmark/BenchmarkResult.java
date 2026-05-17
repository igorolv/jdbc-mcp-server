package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "BenchmarkResult response payload.")
public record BenchmarkResult(
        @Schema(description = "Engine.", nullable = true)
        String engine,
        @Schema(description = "Runs.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int runs,
        @Schema(description = "Cold Runs.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int coldRuns,
        @Schema(description = "Warm Runs.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int warmRuns,
        @Schema(description = "Limit.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int limit,
        @Schema(description = "Timeout Seconds.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int timeoutSeconds,
        @Schema(description = "Cold Ms.", nullable = true)
        TimingStats coldMs,
        @Schema(description = "Warm Ms.", nullable = true)
        TimingStats warmMs,
        @Schema(description = "All Ms.", nullable = true)
        List<Double> allMs,
        @Schema(description = "Result Size.", nullable = true)
        ResultSize resultSize,
        @Schema(description = "Note.", nullable = true)
        String note
) {
}