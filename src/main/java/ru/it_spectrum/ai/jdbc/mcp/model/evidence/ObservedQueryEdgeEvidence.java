package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "Usage-catalog evidence showing how often a table relationship appears as an observed join.")
public record ObservedQueryEdgeEvidence(
        @Schema(description = "Number of known SQL queries that contain this observed join pair.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int joinSupport,
        @Schema(description = "Usage-catalog source records that support this evidence item.", nullable = true)
        List<QuerySourceRef> sourceRefs
) {
    public ObservedQueryEdgeEvidence {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
