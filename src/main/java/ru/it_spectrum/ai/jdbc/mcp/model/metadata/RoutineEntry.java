package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Function, procedure, or package entry returned by routine listing.")
public record RoutineEntry(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Routine type reported by the database, such as FUNCTION, PROCEDURE, or PACKAGE.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type
) {
}