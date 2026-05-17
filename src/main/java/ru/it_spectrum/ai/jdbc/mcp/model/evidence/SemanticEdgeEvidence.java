package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "SemanticEdgeEvidence response payload.")
public record SemanticEdgeEvidence(
        @Schema(description = "Shared Business Domains.", nullable = true)
        List<SemanticTermEvidence> sharedBusinessDomains,
        @Schema(description = "Shared Business Objects.", nullable = true)
        List<SemanticTermEvidence> sharedBusinessObjects,
        @Schema(description = "Shared Output Labels.", nullable = true)
        List<SemanticTermEvidence> sharedOutputLabels,
        @Schema(description = "Co Occurring Query Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int coOccurringQueryCount,
        @Schema(description = "Co Occurring Source Refs.", nullable = true)
        List<QuerySourceRef> coOccurringSourceRefs
) {
    public SemanticEdgeEvidence {
        sharedBusinessDomains = sharedBusinessDomains == null ? List.of() : List.copyOf(sharedBusinessDomains);
        sharedBusinessObjects = sharedBusinessObjects == null ? List.of() : List.copyOf(sharedBusinessObjects);
        sharedOutputLabels = sharedOutputLabels == null ? List.of() : List.copyOf(sharedOutputLabels);
        coOccurringSourceRefs = coOccurringSourceRefs == null ? List.of() : List.copyOf(coOccurringSourceRefs);
    }

    public boolean isEmpty() {
        return coOccurringQueryCount == 0
                && sharedBusinessDomains.isEmpty()
                && sharedBusinessObjects.isEmpty()
                && sharedOutputLabels.isEmpty();
    }
}
