package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;

import java.util.List;

@Schema(description = "Relationship edge in table context, backed by declared foreign keys and optional observed or semantic usage evidence.")
public record RelationshipEdge(
        @Schema(description = "Kind of relationship edge, such as declared foreign key or observed usage join.", nullable = true)
        String relationshipType,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Schema of the source or left-side table in the relationship.", nullable = true)
        String fromSchema,
        @Schema(description = "Source or left-side table in the relationship.", nullable = true)
        String fromTable,
        @Schema(description = "Source-side columns participating in the relationship, in join/key order.", nullable = true)
        List<String> fromColumns,
        @Schema(description = "Schema of the target or right-side table in the relationship.", nullable = true)
        String toSchema,
        @Schema(description = "Target or right-side table in the relationship.", nullable = true)
        String toTable,
        @Schema(description = "Target-side columns participating in the relationship, in join/key order.", nullable = true)
        List<String> toColumns,
        @Schema(description = "True when the relationship can be traversed in either direction for graph search.", nullable = true)
        Boolean undirected,
        @Schema(description = "Evidence explaining why this object or relationship is relevant, from schema and usage sources.", nullable = true)
        RelationshipEvidence evidence
) {
    public RelationshipEdge withEvidence(RelationshipEvidence evidence) {
        return new RelationshipEdge(
                relationshipType, name, fromSchema, fromTable, fromColumns,
                toSchema, toTable, toColumns, undirected, evidence);
    }
}
