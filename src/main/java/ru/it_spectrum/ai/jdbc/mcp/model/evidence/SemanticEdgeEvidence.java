package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticEdgeEvidence(
        List<SemanticTermEvidence> sharedBusinessDomains,
        List<SemanticTermEvidence> sharedBusinessObjects,
        List<SemanticTermEvidence> sharedOutputLabels,
        int coOccurringQueryCount,
        List<String> coOccurringQueryUids
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sharedBusinessDomains", terms(sharedBusinessDomains));
        out.put("sharedBusinessObjects", terms(sharedBusinessObjects));
        out.put("sharedOutputLabels", terms(sharedOutputLabels));
        out.put("coOccurringQueryCount", coOccurringQueryCount);
        out.put("coOccurringQueryUids", coOccurringQueryUids == null ? List.of() : coOccurringQueryUids);
        return out;
    }

    public boolean isEmpty() {
        return coOccurringQueryCount == 0
                && (sharedBusinessDomains == null || sharedBusinessDomains.isEmpty())
                && (sharedBusinessObjects == null || sharedBusinessObjects.isEmpty())
                && (sharedOutputLabels == null || sharedOutputLabels.isEmpty());
    }

    private static List<Map<String, Object>> terms(List<SemanticTermEvidence> terms) {
        return terms == null ? List.of() : terms.stream()
                .map(SemanticTermEvidence::toMap)
                .toList();
    }
}
