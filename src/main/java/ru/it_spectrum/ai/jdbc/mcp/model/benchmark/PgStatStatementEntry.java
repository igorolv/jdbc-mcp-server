package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

public record PgStatStatementEntry(
        String queryid,
        String query,
        long delta_calls,
        double delta_total_exec_time_ms,
        long delta_rows,
        long delta_shared_blks_hit,
        long delta_shared_blks_read
) {
}
