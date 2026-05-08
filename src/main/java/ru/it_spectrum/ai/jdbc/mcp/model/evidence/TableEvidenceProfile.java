package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

public record TableEvidenceProfile(
        String schema,
        String table,
        ObservedTableUsage observedQuery,
        SemanticTableUsage semanticUsage
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("table", table);
        out.put("observedQuery", observedQuery == null ? Map.of() : observedQuery.toMap());
        out.put("semanticUsage", semanticUsage == null ? Map.of() : semanticUsage.toMap());
        return out;
    }
}
