package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "ObservedQueryEdgeEvidence response payload.")
public record ObservedQueryEdgeEvidence(
        @Schema(description = "Join Support.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int joinSupport,
        @Schema(description = "Source Refs.", nullable = true)
        List<QuerySourceRef> sourceRefs
) {
    public ObservedQueryEdgeEvidence {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
