package ru.it_spectrum.ai.jdbc.mcp.model.context;

import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;

import java.util.List;

public record QueryContext(
        String schema,
        String terms,
        List<String> requestedTables,
        boolean includeSamples,
        int tableCount,
        List<SemanticTableCandidate> semanticMatches,
        List<QueryContextTable> tables,
        List<GraphEdgeSummary> relationships,
        List<ShortestPath> joinPaths
) {}
