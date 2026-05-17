package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ShortestPath response payload.")
public record ShortestPath(
        @Schema(description = "From.", nullable = true)
        String from,
        @Schema(description = "To.", nullable = true)
        String to,
        @Schema(description = "Found.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean found,
        @Schema(description = "Edges.", nullable = true)
        List<JoinPathStep> edges
) {
}
