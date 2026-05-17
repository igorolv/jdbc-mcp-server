package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;

import java.util.List;

@Schema(description = "Authoring context selected from schema terms or requested tables, with relevant tables, relationships, and join paths.")
public record QueryContext(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Natural-language terms used to select relevant schema context.", nullable = true)
        String terms,
        @Schema(description = "Explicit table names requested by the caller for query context.", nullable = true)
        List<String> requestedTables,
        @Schema(description = "True when small sample rows were requested for selected tables.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeSamples,
        @Schema(description = "Number of tables selected into the query context.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tableCount,
        @Schema(description = "Tables matched by semantic usage-catalog terms before final context assembly.", nullable = true)
        List<SemanticTableCandidate> semanticMatches,
        @Schema(description = "Tables included in this context, graph, query inspection, or usage record.", nullable = true)
        List<QueryContextTable> tables,
        @Schema(description = "Relationship edges relevant to the context, graph, or observed-relationships result.", nullable = true)
        List<GraphEdgeSummary> relationships,
        @Schema(description = "Shortest or suggested join paths between selected tables.", nullable = true)
        List<ShortestPath> joinPaths
) {}
