package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

public record TableEvidenceProfile(
        String schema,
        String table,
        ObservedTableUsage observedQuery,
        SemanticTableUsage semanticUsage
) {
}
