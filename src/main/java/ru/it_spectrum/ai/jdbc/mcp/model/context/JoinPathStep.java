package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;

import java.util.List;

@Schema(description = "One edge in a suggested join path, including the join condition and supporting evidence.")
public record JoinPathStep(
        @Schema(description = "Traversal direction used by this join-path step relative to the requested route.", nullable = true)
        String direction,
        @Schema(description = "Kind of relationship edge, such as declared foreign key or observed usage join.", nullable = true)
        String relationshipType,
        @Schema(description = "Foreign-key constraint name for declared schema edges, when available.", nullable = true)
        String fkName,
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
        @Schema(description = "SQL equality condition that joins the two tables for this step.", nullable = true)
        String joinCondition,
        @Schema(description = "Evidence explaining why this object or relationship is relevant, from schema and usage sources.", nullable = true)
        RelationshipEvidence evidence
) {
}
