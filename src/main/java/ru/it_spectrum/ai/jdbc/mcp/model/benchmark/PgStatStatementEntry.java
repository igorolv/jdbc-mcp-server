package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "PostgreSQL pg_stat_statements delta for one normalized statement observed while a timed query ran.")
public record PgStatStatementEntry(
        @Schema(description = "Normalized SQL text as tracked by the database statistics view.", nullable = true)
        String query,
        @Schema(description = "Increase in pg_stat_statements call count observed during this timed query.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long deltaCalls,
        @Schema(description = "Increase in total execution time from pg_stat_statements, in milliseconds.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double deltaTotalExecTimeMs,
        @Schema(description = "Increase in rows reported by pg_stat_statements.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long deltaRows,
        @Schema(description = "Increase in shared buffer hits reported by pg_stat_statements.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long deltaSharedBlksHit,
        @Schema(description = "Increase in shared block reads reported by pg_stat_statements.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long deltaSharedBlksRead
) {
}