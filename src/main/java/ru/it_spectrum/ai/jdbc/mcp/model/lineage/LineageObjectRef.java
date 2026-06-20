package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Lightweight database object reference used inside lineage responses.")
public record LineageObjectRef(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type
) {
}
