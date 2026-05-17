package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "SQL parameter placeholder found by query inspection.")
public record QueryParameter(
        @Schema(description = "Parameter placeholder type, such as positional or named.", nullable = true)
        String type,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Original SQL text fragment for this parsed item.", nullable = true)
        String text,
        @Schema(description = "One-based ordinal index for positional parameters, when applicable.", nullable = true)
        Integer index
) {
}
