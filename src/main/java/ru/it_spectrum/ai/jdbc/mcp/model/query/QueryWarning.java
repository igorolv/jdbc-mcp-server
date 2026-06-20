package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Advisory warning produced by query inspection, validation, lint, or lineage analysis.")
public record QueryWarning(
        @Schema(description = "Stable warning or diagnostic code.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String code,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String message
) {
}
