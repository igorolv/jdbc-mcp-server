package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Compact relationship edge between two tables in schema graph and query context responses.")
public record GraphEdgeSummary(
        @Schema(description = "Kind of relationship edge, such as declared foreign key or observed usage join.", nullable = true)
        String relationshipType,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Source node identifier for a graph edge or requested path.", nullable = true)
        String from,
        @Schema(description = "Target node identifier for a graph edge or requested path.", nullable = true)
        String to,
        @Schema(description = "Source or left-side table in the relationship.", nullable = true)
        String fromTable,
        @Schema(description = "Source-side columns participating in the relationship, in join/key order.", nullable = true)
        List<String> fromColumns,
        @Schema(description = "Target or right-side table in the relationship.", nullable = true)
        String toTable,
        @Schema(description = "Target-side columns participating in the relationship, in join/key order.", nullable = true)
        List<String> toColumns
) {
}
