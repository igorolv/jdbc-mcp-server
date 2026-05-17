package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Optional PostgreSQL pg_stat_statements summary attached to timed query results when the extension is available.")
public record PgStatStatements(
        @Schema(description = "True when the optional database statistics source was available to the current connection.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean available,
        @Schema(description = "Number of pg_stat_statements entries whose counters changed while the query ran.", nullable = true)
        Integer changedEntries,
        @Schema(description = "Changed pg_stat_statements entries relevant to the timed query.", nullable = true)
        List<PgStatStatementEntry> entries,
        @Schema(description = "Additional context about support, limits, interpretation, or engine-specific behavior.", nullable = true)
        String note
) {
}