package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "QuerySourceRef response payload.")
public record QuerySourceRef(
        @Schema(description = "Source Kind.", nullable = true)
        String sourceKind,
        @Schema(description = "Source Path.", nullable = true)
        String sourcePath,
        @Schema(description = "Source Unit.", nullable = true)
        String sourceUnit
) {
    public QuerySourceRef {
        if (sourceKind == null || sourceKind.isBlank()) {
            throw new IllegalArgumentException("sourceKind is required");
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("sourcePath is required");
        }
    }
}
