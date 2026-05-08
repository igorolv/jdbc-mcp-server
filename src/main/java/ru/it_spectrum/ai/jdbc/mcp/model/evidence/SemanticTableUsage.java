package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticTableUsage(
        List<SemanticTermEvidence> businessDomains,
        List<SemanticTermEvidence> businessTags,
        List<SemanticTermEvidence> queryLabels,
        List<SemanticTermEvidence> outputLabels,
        List<SemanticTermEvidence> businessObjects,
        List<SemanticColumnUsage> columns
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDomains", terms(businessDomains));
        out.put("businessTags", terms(businessTags));
        out.put("queryLabels", terms(queryLabels));
        out.put("outputLabels", terms(outputLabels));
        out.put("businessObjects", terms(businessObjects));
        out.put("columns", columns == null ? List.of() : columns.stream()
                .map(SemanticColumnUsage::toMap)
                .toList());
        return out;
    }

    private static List<Map<String, Object>> terms(List<SemanticTermEvidence> terms) {
        return terms == null ? List.of() : terms.stream()
                .map(SemanticTermEvidence::toMap)
                .toList();
    }
}
