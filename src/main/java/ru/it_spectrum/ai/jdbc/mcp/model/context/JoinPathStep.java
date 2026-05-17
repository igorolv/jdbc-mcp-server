package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;

import java.util.List;

@Schema(description = "JoinPathStep response payload.")
public record JoinPathStep(
        @Schema(description = "Direction.", nullable = true)
        String direction,
        @Schema(description = "Relationship Type.", nullable = true)
        String relationshipType,
        @Schema(description = "Fk Name.", nullable = true)
        String fkName,
        @Schema(description = "From Schema.", nullable = true)
        String fromSchema,
        @Schema(description = "From Table.", nullable = true)
        String fromTable,
        @Schema(description = "From Columns.", nullable = true)
        List<String> fromColumns,
        @Schema(description = "To Schema.", nullable = true)
        String toSchema,
        @Schema(description = "To Table.", nullable = true)
        String toTable,
        @Schema(description = "To Columns.", nullable = true)
        List<String> toColumns,
        @Schema(description = "Join Condition.", nullable = true)
        String joinCondition,
        @Schema(description = "Evidence.", nullable = true)
        RelationshipEvidence evidence
) {
}
