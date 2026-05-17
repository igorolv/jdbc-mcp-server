package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Observed join relationships aggregated from known SQL queries.")
public record ObservedRelationshipsResult(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Optional table filter used when aggregating observed relationships.", nullable = true)
        String table,
        @Schema(description = "Minimum observed support threshold applied to relationship aggregation.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int minSupport,
        @Schema(description = "Relationship edges relevant to the context, graph, or observed-relationships result.", nullable = true)
        List<Relationship> relationships,
        @Schema(description = "Number of observed relationships returned after filtering.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count
) {
    @Schema(description = "Observed equi-join relationship with support count and contributing source queries.")
    public record Relationship(
            @Schema(description = "Left side of an observed or documented join relationship.", nullable = true)
            SchemaRef left,
            @Schema(description = "Right side of an observed or documented join relationship.", nullable = true)
            SchemaRef right,
            @Schema(description = "Number of catalog queries that contain this observed join relationship.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int support,
            @Schema(description = "Usage-catalog source records that support this evidence item.", nullable = true)
            List<QuerySourceRef> sourceRefs
    ) {
        public Relationship {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
    @Schema(description = "Schema-qualified table column reference used in usage-catalog relationship and lineage details.")
    public record SchemaRef(
            @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
            String schema,
            @Schema(description = "Table name within the schema.", nullable = true)
            String table,
            @Schema(description = "Column name within the table.", nullable = true)
            String column
    ) {
    }
}
