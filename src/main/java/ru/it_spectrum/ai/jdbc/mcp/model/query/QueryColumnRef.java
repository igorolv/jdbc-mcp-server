package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "QueryColumnRef response payload.")
public record QueryColumnRef(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Qualifier.", nullable = true)
        String qualifier,
        @Schema(description = "Text.", nullable = true)
        String text,
        @Schema(description = "Context.", nullable = true)
        String context
) {
}
