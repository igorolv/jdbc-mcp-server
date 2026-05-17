package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "LineageObjectRef response payload.")
public record LineageObjectRef(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type
) {
}
