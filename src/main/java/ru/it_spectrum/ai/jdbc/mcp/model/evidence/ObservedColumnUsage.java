package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "ObservedColumnUsage response payload.")
public record ObservedColumnUsage(
        @Schema(description = "Column.", nullable = true)
        String column,
        @Schema(description = "Query Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int queryCount,
        @Schema(description = "Contexts.", nullable = true)
        List<SemanticTermEvidence> contexts,
        @Schema(description = "Source Refs.", nullable = true)
        List<QuerySourceRef> sourceRefs
) {
    public ObservedColumnUsage {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
