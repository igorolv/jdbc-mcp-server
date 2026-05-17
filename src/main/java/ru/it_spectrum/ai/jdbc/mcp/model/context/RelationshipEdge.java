package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;

import java.util.List;

@Schema(description = "RelationshipEdge response payload.")
public record RelationshipEdge(
        @Schema(description = "Relationship Type.", nullable = true)
        String relationshipType,
        @Schema(description = "Name.", nullable = true)
        String name,
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
        @Schema(description = "Undirected.", nullable = true)
        Boolean undirected,
        @Schema(description = "Evidence.", nullable = true)
        RelationshipEvidence evidence
) {
    public RelationshipEdge withEvidence(RelationshipEvidence evidence) {
        return new RelationshipEdge(
                relationshipType, name, fromSchema, fromTable, fromColumns,
                toSchema, toTable, toColumns, undirected, evidence);
    }
}
