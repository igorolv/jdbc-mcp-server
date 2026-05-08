package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticTableCandidate(
        String schema,
        String table,
        int support,
        List<SemanticTermEvidence> matchedTerms,
        List<String> queryUids
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("table", table);
        out.put("support", support);
        out.put("matchedTerms", matchedTerms == null ? List.of() : matchedTerms.stream()
                .map(SemanticTermEvidence::toMap)
                .toList());
        out.put("queryUids", queryUids == null ? List.of() : queryUids);
        return out;
    }
}
