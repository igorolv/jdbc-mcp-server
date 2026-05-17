package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "Usage-catalog evidence showing how often a column appears in known SQL queries.")
public record ObservedColumnUsage(
        @Schema(description = "Column name within the table.", nullable = true)
        String column,
        @Schema(description = "Number of known SQL queries that reference this object or term.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int queryCount,
        @Schema(description = "Contexts in which the column appeared, such as select, where, join, order_by, or having.", nullable = true)
        List<SemanticTermEvidence> contexts,
        @Schema(description = "Usage-catalog source records that support this evidence item.", nullable = true)
        List<QuerySourceRef> sourceRefs
) {
    public ObservedColumnUsage {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
