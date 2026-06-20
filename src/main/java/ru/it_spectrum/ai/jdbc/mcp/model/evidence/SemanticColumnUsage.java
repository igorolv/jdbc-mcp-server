package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Semantic usage labels tied to a column, derived from known query outputs and business objects.")
public record SemanticColumnUsage(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String column,
        @Schema(description = "Business output labels associated with this table or column.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<SemanticTermEvidence> outputLabels,
        @Schema(description = "Business object names associated with this table, column, or relationship.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<SemanticTermEvidence> businessObjects
) {
    public SemanticColumnUsage {
        outputLabels = outputLabels == null ? List.of() : List.copyOf(outputLabels);
        businessObjects = businessObjects == null ? List.of() : List.copyOf(businessObjects);
    }
}
