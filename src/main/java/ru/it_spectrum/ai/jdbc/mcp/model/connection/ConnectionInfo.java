package ru.it_spectrum.ai.jdbc.mcp.model.connection;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One database this server can be pointed at with the 'connection' tool argument.")
public record ConnectionInfo(
        @Schema(description = "Value to pass as 'connection'.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String name,
        @Schema(description = "What this database is for, as configured by the operator.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description,
        @Schema(description = "Engine: PostgreSQL, Oracle or SQL Server. Absent when the URL is unusable.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String kind,
        @Schema(description = "Schema used by metadata tools when a call omits one.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String defaultSchema,
        @Schema(description = "True when a local catalog file already exists for this connection.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean snapshotAvailable,
        @Schema(description = "True when this connection's pool has already been built in this process.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean initialized,
        @Schema(description = "Why this connection cannot be used; absent when it is usable.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String configError
) {
}
