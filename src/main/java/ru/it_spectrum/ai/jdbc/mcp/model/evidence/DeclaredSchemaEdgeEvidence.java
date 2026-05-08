package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DeclaredSchemaEdgeEvidence(
        String foreignKeyName,
        List<String> fromColumns,
        List<String> toColumns
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("foreignKeyName", foreignKeyName);
        out.put("fromColumns", fromColumns == null ? List.of() : fromColumns);
        out.put("toColumns", toColumns == null ? List.of() : toColumns);
        return out;
    }
}
