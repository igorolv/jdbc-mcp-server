package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "LineageExpandedObject response payload.")
public record LineageExpandedObject(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Depends On.", nullable = true)
        List<LineageObjectRef> dependsOn,
        @Schema(description = "Via.", nullable = true)
        List<String> via,
        @Schema(description = "Depth.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int depth,
        @Schema(description = "Confidence.", nullable = true)
        String confidence
) {
}
