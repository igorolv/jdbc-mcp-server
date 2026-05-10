package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

public record SemanticEdgeEvidence(
        List<SemanticTermEvidence> sharedBusinessDomains,
        List<SemanticTermEvidence> sharedBusinessObjects,
        List<SemanticTermEvidence> sharedOutputLabels,
        int coOccurringQueryCount,
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
