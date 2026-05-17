package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "SemanticTermEvidence response payload.")
public record SemanticTermEvidence(
        @Schema(description = "Value.", nullable = true)
        String value,
        @Schema(description = "Support.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int support,
        @Schema(description = "Source Refs.", nullable = true)
        List<QuerySourceRef> sourceRefs
) {
    public SemanticTermEvidence {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
