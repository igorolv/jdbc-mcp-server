package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryInspection;

import java.util.List;

@Schema(description = "QueryLineageResult response payload.")
public record QueryLineageResult(
        @Schema(description = "Inspection.", nullable = true)
        QueryInspection inspection,
        @Schema(description = "Direct Objects.", nullable = true)
        List<LineageDirectObject> directObjects,
        @Schema(description = "Expanded Physical Tables.", nullable = true)
        List<LineagePhysicalTable> expandedPhysicalTables,
        @Schema(description = "Expanded Objects.", nullable = true)
        List<LineageExpandedObject> expandedObjects,
        @Schema(description = "Unresolved Objects.", nullable = true)
        List<LineageUnresolvedObject> unresolvedObjects,
        @Schema(description = "Cycles.", nullable = true)
        List<LineageCycle> cycles,
        @Schema(description = "Warnings.", nullable = true)
        List<LineageWarning> warnings,
        @Schema(description = "Max Depth.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int maxDepth
) {
}
