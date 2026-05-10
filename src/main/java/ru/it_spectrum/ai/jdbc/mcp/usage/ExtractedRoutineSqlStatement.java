package ru.it_spectrum.ai.jdbc.mcp.usage;

public record ExtractedRoutineSqlStatement(
        String routineName,
        String routineKind,
        int ordinal,
        String kind,
        String sql
) {
}
