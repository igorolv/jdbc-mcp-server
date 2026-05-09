package ru.it_spectrum.ai.jdbc.mcp.model.context;

import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;

import java.util.List;

public record JoinPathStep(
        String direction,
        String relationshipType,
        String fkName,
        String fromSchema,
        String fromTable,
        List<String> fromColumns,
        String toSchema,
        String toTable,
        List<String> toColumns,
        String joinCondition,
        RelationshipEvidence evidence
) {
}
