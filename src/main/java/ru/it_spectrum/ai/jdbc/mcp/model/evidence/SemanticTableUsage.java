package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record SemanticTableUsage(
        List<SemanticTermEvidence> businessDomains,
        List<SemanticTermEvidence> businessTags,
        List<SemanticTermEvidence> queryLabels,
        List<SemanticTermEvidence> outputLabels,
        List<SemanticTermEvidence> businessObjects,
        List<SemanticColumnUsage> columns
) {
    public SemanticTableUsage {
        businessDomains = businessDomains == null ? List.of() : List.copyOf(businessDomains);
        businessTags = businessTags == null ? List.of() : List.copyOf(businessTags);
        queryLabels = queryLabels == null ? List.of() : List.copyOf(queryLabels);
        outputLabels = outputLabels == null ? List.of() : List.copyOf(outputLabels);
        businessObjects = businessObjects == null ? List.of() : List.copyOf(businessObjects);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
