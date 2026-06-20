package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Placeholder table context returned when metadata for a table could not be loaded.")
public record ErrorTable(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String remarks,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error
) implements ContextTable {
}
