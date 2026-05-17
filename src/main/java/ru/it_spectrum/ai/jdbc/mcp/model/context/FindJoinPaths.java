package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "FindJoinPaths response payload.")
public record FindJoinPaths(
        @Schema(description = "From Schema.", nullable = true)
        String fromSchema,
        @Schema(description = "From Table.", nullable = true)
        String fromTable,
        @Schema(description = "To Schema.", nullable = true)
        String toSchema,
        @Schema(description = "To Table.", nullable = true)
        String toTable,
        @Schema(description = "Max Depth.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int maxDepth,
        @Schema(description = "Include Observed.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeObserved,
        @Schema(description = "Schema Tables Scanned.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int schemaTablesScanned,
        @Schema(description = "Path Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int pathCount,
        @Schema(description = "Paths.", nullable = true)
        List<List<JoinPathStep>> paths
) {}
