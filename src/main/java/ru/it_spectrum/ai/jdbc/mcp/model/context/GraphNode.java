package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "GraphNode response payload.")
public record GraphNode(
        @Schema(description = "Id.", nullable = true)
        String id,
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Classification.", nullable = true)
        String classification,
        @Schema(description = "Incoming Degree.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int incomingDegree,
        @Schema(description = "Outgoing Degree.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int outgoingDegree,
        @Schema(description = "Total Degree.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int totalDegree,
        @Schema(description = "Column Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int columnCount,
        @Schema(description = "Primary Key.", nullable = true)
        List<String> primaryKey
) {
}
