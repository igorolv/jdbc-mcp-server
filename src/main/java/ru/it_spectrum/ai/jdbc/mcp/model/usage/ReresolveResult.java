package ru.it_spectrum.ai.jdbc.mcp.model.usage;

public record ReresolveResult(
        int namesLookedUp,
        int tablesResolved,
        int tablesAmbiguous,
        int tablesUnresolved
) {
}