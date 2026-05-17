package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Placeholder table context returned when metadata for a table could not be loaded.")
public record ErrorTable(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Database object type, SQL construct type, or engine-specific classification.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String remarks,
        @Schema(description = "Human-readable error message explaining why the requested operation failed.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error
) implements ContextTable {
}
