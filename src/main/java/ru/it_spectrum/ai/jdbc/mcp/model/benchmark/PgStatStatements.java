package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "PgStatStatements response payload.")
public record PgStatStatements(
        @Schema(description = "Available.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean available,
        @Schema(description = "Changed Entries.", nullable = true)
        Integer changedEntries,
        @Schema(description = "Entries.", nullable = true)
        List<PgStatStatementEntry> entries,
        @Schema(description = "Note.", nullable = true)
        String note
) {
}