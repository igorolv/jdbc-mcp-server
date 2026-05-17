package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "LineagePhysicalTable response payload.")
public record LineagePhysicalTable(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Via.", nullable = true)
        List<String> via,
        @Schema(description = "Depth.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int depth
) {
}
