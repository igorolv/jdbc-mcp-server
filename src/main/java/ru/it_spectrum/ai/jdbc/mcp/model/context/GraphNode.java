package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Table node in the schema relationship graph with degree and key metadata.")
public record GraphNode(
        @Schema(description = "Stable graph node identifier, usually schema-qualified table name.", nullable = true)
        String id,
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true)
        String table,
        @Schema(description = "Heuristic table role in the schema graph, such as central, isolated, lookup, or regular.", nullable = true)
        String classification,
        @Schema(description = "Number of relationship edges entering this table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int incomingDegree,
        @Schema(description = "Number of relationship edges leaving this table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int outgoingDegree,
        @Schema(description = "Total number of relationship edges connected to this table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int totalDegree,
        @Schema(description = "Number of columns visible for the table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int columnCount,
        @Schema(description = "Primary-key columns for the table, in key order.", nullable = true)
        List<String> primaryKey
) {
}
