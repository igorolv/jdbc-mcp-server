package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Foreign-key and observed-usage join paths between two tables, capped for concise LLM context.")
public record FindJoinPaths(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromSchema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromTable,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String toSchema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String toTable,
        @Schema(description = "Maximum relationship traversal depth that was applied.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int maxDepth,
        @Schema(description = "True when usage-catalog observed joins were included as relationship evidence.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeObserved,
        @Schema(description = "Number of schema tables inspected while searching the relationship graph.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int schemaTablesScanned,
        @Schema(description = "Number of join paths returned after caps were applied.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int pathCount,
        @Schema(description = "Join paths from the source table to the target table; each path is an ordered list of steps.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<List<JoinPathStep>> paths
) {}
