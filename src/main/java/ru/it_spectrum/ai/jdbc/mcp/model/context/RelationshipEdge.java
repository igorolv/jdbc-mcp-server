package ru.it_spectrum.ai.jdbc.mcp.model.context;

import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;

import java.util.List;

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
        RelationshipEvidence evidence
) {
    public RelationshipEdge withEvidence(RelationshipEvidence evidence) {
        return new RelationshipEdge(
                relationshipType, fkName, fromSchema, fromTable, fromColumns,
                toSchema, toTable, toColumns, undirected, evidence);
    }
}
