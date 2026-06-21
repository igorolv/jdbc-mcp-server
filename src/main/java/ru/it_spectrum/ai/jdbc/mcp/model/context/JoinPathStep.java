package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.Opaque;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;

import java.util.List;

@Schema(description = "One edge in a suggested join path, including the join condition and supporting evidence.")
public record JoinPathStep(
        @Schema(description = "Traversal direction used by this join-path step relative to the requested route.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String direction,
        @Schema(description = "Kind of relationship edge, such as declared foreign key or observed usage join.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String relationshipType,
        @Schema(description = "Foreign-key constraint name for declared schema edges, when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fkName,
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
        @Schema(description = "SQL equality condition that joins the two tables for this step.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String joinCondition,
        @Schema(description = "Evidence explaining why this object or relationship is relevant, from schema and usage sources (opaque).", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Opaque<RelationshipEvidence> evidence
) {
}
