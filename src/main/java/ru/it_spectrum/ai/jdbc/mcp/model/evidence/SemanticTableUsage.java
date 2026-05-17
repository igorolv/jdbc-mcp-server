package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Semantic usage profile for a table, based on business domains, tags, labels, outputs, and business objects.")
public record SemanticTableUsage(
        @Schema(description = "Business domains attached to known queries that use this object.", nullable = true)
        List<SemanticTermEvidence> businessDomains,
        @Schema(description = "Business tags attached to known queries that use this object.", nullable = true)
        List<SemanticTermEvidence> businessTags,
        @Schema(description = "Business labels of known queries that use this object.", nullable = true)
        List<SemanticTermEvidence> queryLabels,
        @Schema(description = "Business output labels associated with this table or column.", nullable = true)
        List<SemanticTermEvidence> outputLabels,
        @Schema(description = "Business object names associated with this table, column, or relationship.", nullable = true)
        List<SemanticTermEvidence> businessObjects,
        @Schema(description = "Semantic usage details for columns associated with this table.", nullable = true)
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
