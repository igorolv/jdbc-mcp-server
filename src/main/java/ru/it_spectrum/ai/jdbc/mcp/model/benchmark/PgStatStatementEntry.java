package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

public record PgStatStatementEntry(
        String queryid,
        String query,
        long deltaCalls,
        double deltaTotalExecTimeMs,
        long deltaRows,
        long deltaSharedBlksHit,
        long deltaSharedBlksRead
) {
}