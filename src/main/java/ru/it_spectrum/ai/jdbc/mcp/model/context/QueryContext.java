package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;

import java.util.List;

@Schema(description = "QueryContext response payload.")
public record QueryContext(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Terms.", nullable = true)
        String terms,
        @Schema(description = "Requested Tables.", nullable = true)
        List<String> requestedTables,
        @Schema(description = "Include Samples.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeSamples,
        @Schema(description = "Table Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tableCount,
        @Schema(description = "Semantic Matches.", nullable = true)
        List<SemanticTableCandidate> semanticMatches,
        @Schema(description = "Tables.", nullable = true)
        List<QueryContextTable> tables,
        @Schema(description = "Relationships.", nullable = true)
        List<GraphEdgeSummary> relationships,
        @Schema(description = "Join Paths.", nullable = true)
        List<ShortestPath> joinPaths
) {}
