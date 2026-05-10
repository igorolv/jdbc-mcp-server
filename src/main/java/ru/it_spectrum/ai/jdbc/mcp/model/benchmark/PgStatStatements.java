package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import java.util.List;

public record PgStatStatements(
        boolean available,
        Integer changedEntries,
        List<PgStatStatementEntry> entries,
        String note
) {
}