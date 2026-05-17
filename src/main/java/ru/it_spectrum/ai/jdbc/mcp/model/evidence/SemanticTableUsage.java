package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "SemanticTableUsage response payload.")
public record SemanticTableUsage(
        @Schema(description = "Business Domains.", nullable = true)
        List<SemanticTermEvidence> businessDomains,
        @Schema(description = "Business Tags.", nullable = true)
        List<SemanticTermEvidence> businessTags,
        @Schema(description = "Query Labels.", nullable = true)
        List<SemanticTermEvidence> queryLabels,
        @Schema(description = "Output Labels.", nullable = true)
        List<SemanticTermEvidence> outputLabels,
        @Schema(description = "Business Objects.", nullable = true)
        List<SemanticTermEvidence> businessObjects,
        @Schema(description = "Columns.", nullable = true)
        List<SemanticColumnUsage> columns
) {
    public SemanticTableUsage {
        businessDomains = businessDomains == null ? List.of() : List.copyOf(businessDomains);
        businessTags = businessTags == null ? List.of() : List.copyOf(businessTags);
        queryLabels = queryLabels == null ? List.of() : List.copyOf(queryLabels);
        outputLabels = outputLabels == null ? List.of() : List.copyOf(outputLabels);
        businessObjects = businessObjects == null ? List.of() : List.copyOf(businessObjects);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
