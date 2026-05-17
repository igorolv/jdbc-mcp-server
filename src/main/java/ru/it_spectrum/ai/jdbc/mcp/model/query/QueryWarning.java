package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "QueryWarning response payload.")
public record QueryWarning(
        @Schema(description = "Code.", nullable = true)
        String code,
        @Schema(description = "Message.", nullable = true)
        String message
) {
}
