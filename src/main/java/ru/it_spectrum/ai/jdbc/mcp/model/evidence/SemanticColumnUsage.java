package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "SemanticColumnUsage response payload.")
public record SemanticColumnUsage(
        @Schema(description = "Column.", nullable = true)
        String column,
        @Schema(description = "Output Labels.", nullable = true)
        List<SemanticTermEvidence> outputLabels,
        @Schema(description = "Business Objects.", nullable = true)
        List<SemanticTermEvidence> businessObjects
) {
    public SemanticColumnUsage {
        outputLabels = outputLabels == null ? List.of() : List.copyOf(outputLabels);
        businessObjects = businessObjects == null ? List.of() : List.copyOf(businessObjects);
    }
}
