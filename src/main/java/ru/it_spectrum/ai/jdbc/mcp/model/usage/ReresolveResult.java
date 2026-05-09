package ru.it_spectrum.ai.jdbc.mcp.model.usage;

public record ReresolveResult(
        String dataSource,
        int namesLookedUp,
        int tablesResolved,
        int tablesAmbiguous,
        int tablesUnresolved
) {
}