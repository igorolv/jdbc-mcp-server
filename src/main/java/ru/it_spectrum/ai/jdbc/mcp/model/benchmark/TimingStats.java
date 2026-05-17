package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Aggregate timing statistics for a group of benchmark runs, expressed in milliseconds.")
public record TimingStats(
        @Schema(description = "Number of measured runs included in these timing statistics.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int runs,
        @Schema(description = "Minimum observed elapsed time in milliseconds.", nullable = true)
        Double min,
        @Schema(description = "Median observed elapsed time in milliseconds.", nullable = true)
        Double median,
        @Schema(description = "Maximum observed elapsed time or value in this result.", nullable = true)
        Double max
) {
}
