package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Evidence bundle for a table, combining observed SQL usage and semantic usage signals.")
public record TableEvidenceProfile(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true)
        String table,
        @Schema(description = "Evidence from known SQL queries in the usage catalog.", nullable = true)
        ObservedTableUsage observedQuery,
        @Schema(description = "Evidence from business labels, domains, tags, outputs, and field usage in the catalog.", nullable = true)
        SemanticTableUsage semanticUsage
) {
}
