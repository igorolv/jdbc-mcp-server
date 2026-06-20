package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Observed join relationships aggregated from known SQL queries.")
public record ObservedRelationshipsResult(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Optional table filter used when aggregating observed relationships.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Minimum observed support threshold applied to relationship aggregation.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int minSupport,
        @Schema(description = "Relationship edges relevant to the context, graph, or observed-relationships result.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Relationship> relationships,
        @Schema(description = "Number of observed relationships returned after filtering.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count
) {
    @Schema(description = "Observed equi-join relationship with support count and contributing source queries.")
    public record Relationship(
            @Schema(description = "Left side of an observed or documented join relationship.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            SchemaRef left,
            @Schema(description = "Right side of an observed or documented join relationship.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            SchemaRef right,
            @Schema(description = "Number of catalog queries that contain this observed join relationship.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int support,
            @Schema(description = "Usage-catalog source records that support this evidence item.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            List<QuerySourceRef> sourceRefs
    ) {
        public Relationship {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
    @Schema(description = "Schema-qualified table column reference used in usage-catalog relationship and lineage details.")
    public record SchemaRef(
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String schema,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String table,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String column
    ) {
    }
}
