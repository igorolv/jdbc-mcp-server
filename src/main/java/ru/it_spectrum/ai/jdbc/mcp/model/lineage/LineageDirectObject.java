package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "LineageDirectObject response payload.")
public record LineageDirectObject(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Alias.", nullable = true)
        String alias,
        @Schema(description = "Source.", nullable = true)
        String source,
        @Schema(description = "Resolution Status.", nullable = true)
        String resolutionStatus
) {
}
