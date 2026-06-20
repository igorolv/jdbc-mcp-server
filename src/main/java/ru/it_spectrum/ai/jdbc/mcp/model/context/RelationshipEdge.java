package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;

import java.util.List;

@Schema(description = "Relationship edge in table context, backed by declared foreign keys and optional observed or semantic usage evidence.")
public record RelationshipEdge(
        @Schema(description = "Kind of relationship edge, such as declared foreign key or observed usage join.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String relationshipType,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromSchema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromTable,
        @Schema(description = "Source-side columns participating in the relationship, in join/key order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> fromColumns,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String toSchema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String toTable,
        @Schema(description = "Target-side columns participating in the relationship, in join/key order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> toColumns,
        @Schema(description = "True when the relationship can be traversed in either direction for graph search.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean undirected,
        @Schema(description = "Evidence explaining why this object or relationship is relevant, from schema and usage sources.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        RelationshipEvidence evidence
) {
    public RelationshipEdge withEvidence(RelationshipEvidence evidence) {
        return new RelationshipEdge(
                relationshipType, name, fromSchema, fromTable, fromColumns,
                toSchema, toTable, toColumns, undirected, evidence);
    }
}
