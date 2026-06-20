package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "SQL parameter placeholder found by query inspection.")
public record QueryParameter(
        @Schema(description = "Parameter placeholder type, such as positional or named.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Original SQL text fragment for this parsed item.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String text,
        @Schema(description = "One-based ordinal index for positional parameters, when applicable.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer index
) {
}
