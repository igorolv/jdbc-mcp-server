package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "GraphEdgeSummary response payload.")
public record GraphEdgeSummary(
        @Schema(description = "Relationship Type.", nullable = true)
        String relationshipType,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "From.", nullable = true)
        String from,
        @Schema(description = "To.", nullable = true)
        String to,
        @Schema(description = "From Table.", nullable = true)
        String fromTable,
        @Schema(description = "From Columns.", nullable = true)
        List<String> fromColumns,
        @Schema(description = "To Table.", nullable = true)
        String toTable,
        @Schema(description = "To Columns.", nullable = true)
        List<String> toColumns
) {
}
