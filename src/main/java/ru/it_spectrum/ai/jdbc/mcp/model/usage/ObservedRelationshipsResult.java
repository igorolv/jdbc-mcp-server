package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ObservedRelationshipsResult response payload.")
public record ObservedRelationshipsResult(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Min Support.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int minSupport,
        @Schema(description = "Relationships.", nullable = true)
        List<Relationship> relationships,
        @Schema(description = "Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count
) {
    @Schema(description = "Relationship response payload.")
    public record Relationship(
            @Schema(description = "Left.", nullable = true)
            SchemaRef left,
            @Schema(description = "Right.", nullable = true)
            SchemaRef right,
            @Schema(description = "Support.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int support,
            @Schema(description = "Source Refs.", nullable = true)
            List<QuerySourceRef> sourceRefs
    ) {
        public Relationship {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
    @Schema(description = "SchemaRef response payload.")
    public record SchemaRef(
            @Schema(description = "Schema.", nullable = true)
            String schema,
            @Schema(description = "Table.", nullable = true)
            String table,
            @Schema(description = "Column.", nullable = true)
            String column
    ) {
    }
}