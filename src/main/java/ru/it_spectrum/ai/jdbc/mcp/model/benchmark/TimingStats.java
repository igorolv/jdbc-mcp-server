package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "TimingStats response payload.")
public record TimingStats(
        @Schema(description = "Runs.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int runs,
        @Schema(description = "Min.", nullable = true)
        Double min,
        @Schema(description = "Median.", nullable = true)
        Double median,
        @Schema(description = "Max.", nullable = true)
        Double max
) {
}
