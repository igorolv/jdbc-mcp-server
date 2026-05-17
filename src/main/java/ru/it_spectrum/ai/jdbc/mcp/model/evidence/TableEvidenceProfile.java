package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "TableEvidenceProfile response payload.")
public record TableEvidenceProfile(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Observed Query.", nullable = true)
        ObservedTableUsage observedQuery,
        @Schema(description = "Semantic Usage.", nullable = true)
        SemanticTableUsage semanticUsage
) {
}
