package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "QueryTableRef response payload.")
public record QueryTableRef(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Full Name.", nullable = true)
        String fullName,
        @Schema(description = "Alias.", nullable = true)
        String alias,
        @Schema(description = "Source.", nullable = true)
        String source
) {
}
