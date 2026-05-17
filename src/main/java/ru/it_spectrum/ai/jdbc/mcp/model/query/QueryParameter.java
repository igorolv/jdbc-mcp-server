package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "QueryParameter response payload.")
public record QueryParameter(
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Text.", nullable = true)
        String text,
        @Schema(description = "Index.", nullable = true)
        Integer index
) {
}
