package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "PgStatStatementEntry response payload.")
public record PgStatStatementEntry(
        @Schema(description = "Query.", nullable = true)
        String query,
        @Schema(description = "Delta Calls.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long deltaCalls,
        @Schema(description = "Delta Total Exec Time Ms.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double deltaTotalExecTimeMs,
        @Schema(description = "Delta Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long deltaRows,
        @Schema(description = "Delta Shared Blks Hit.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long deltaSharedBlksHit,
        @Schema(description = "Delta Shared Blks Read.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long deltaSharedBlksRead
) {
}