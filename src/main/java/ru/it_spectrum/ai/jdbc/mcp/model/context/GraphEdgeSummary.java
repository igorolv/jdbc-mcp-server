package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Compact relationship edge between two tables in schema graph and query context responses.")
public record GraphEdgeSummary(
        @Schema(description = "Kind of relationship edge, such as declared foreign key or observed usage join.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String relationshipType,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Source node identifier for a graph edge or requested path.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String from,
        @Schema(description = "Target node identifier for a graph edge or requested path.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String to,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromTable,
        @Schema(description = "Source-side columns participating in the relationship, in join/key order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> fromColumns,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String toTable,
        @Schema(description = "Target-side columns participating in the relationship, in join/key order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> toColumns
) {
}
