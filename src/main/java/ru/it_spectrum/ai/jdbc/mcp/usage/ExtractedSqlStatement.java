package ru.it_spectrum.ai.jdbc.mcp.usage;

public record ExtractedSqlStatement(
        int ordinal,
        String kind,
        String sql
) {
}
