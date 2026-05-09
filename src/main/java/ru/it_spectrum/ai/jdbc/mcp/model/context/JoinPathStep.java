package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;

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
        Map<String, Object> evidence
) {
}
