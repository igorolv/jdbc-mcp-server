package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "LineageWarning response payload.")
public record LineageWarning(
        @Schema(description = "Code.", nullable = true)
        String code,
        @Schema(description = "Message.", nullable = true)
        String message
) {
}
