package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "Term-level semantic evidence with support count and source queries.")
public record SemanticTermEvidence(
        @Schema(description = "Column value for this distribution bucket; may be null.", nullable = true)
        String value,
        @Schema(description = "Number of source records or terms supporting this semantic candidate.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int support,
        @Schema(description = "Usage-catalog source records that support this evidence item.", nullable = true)
        List<QuerySourceRef> sourceRefs
) {
    public SemanticTermEvidence {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
