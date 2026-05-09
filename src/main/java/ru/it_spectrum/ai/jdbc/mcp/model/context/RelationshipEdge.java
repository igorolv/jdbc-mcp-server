package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;

public record RelationshipEdge(
        String relationshipType,
        String fkName,
        String fromSchema,
        String fromTable,
        List<String> fromColumns,
        String toSchema,
        String toTable,
        List<String> toColumns,
        Boolean undirected,
        Map<String, Object> evidence
) {
    public RelationshipEdge withEvidence(Map<String, Object> evidence) {
        return new RelationshipEdge(
                relationshipType, fkName, fromSchema, fromTable, fromColumns,
                toSchema, toTable, toColumns, undirected, evidence);
    }
}
