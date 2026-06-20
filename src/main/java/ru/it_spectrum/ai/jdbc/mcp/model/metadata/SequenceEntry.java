package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Database sequence found by metadata listing.")
public record SequenceEntry(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name
) {
}