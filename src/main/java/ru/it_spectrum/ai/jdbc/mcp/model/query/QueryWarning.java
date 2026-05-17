package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Advisory warning produced by query inspection, validation, lint, or lineage analysis.")
public record QueryWarning(
        @Schema(description = "Stable warning or diagnostic code.", nullable = true)
        String code,
        @Schema(description = "Human-readable diagnostic message.", nullable = true)
        String message
) {
}
