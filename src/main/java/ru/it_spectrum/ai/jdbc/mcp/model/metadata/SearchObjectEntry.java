package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Database object found by cross-object metadata search.")
public record SearchObjectEntry(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Database object type returned by search, such as TABLE, VIEW, ROUTINE, SEQUENCE, or SYNONYM.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type
) {
}