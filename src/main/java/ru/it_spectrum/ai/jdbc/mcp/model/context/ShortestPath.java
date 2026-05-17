package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Shortest relationship path between two tables in the schema graph.")
public record ShortestPath(
        @Schema(description = "Source node identifier for a graph edge or requested path.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String from,
        @Schema(description = "Target node identifier for a graph edge or requested path.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String to,
        @Schema(description = "True when the requested object or path was found.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean found,
        @Schema(description = "Ordered relationship steps that form the shortest path.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<JoinPathStep> edges
) {
}
