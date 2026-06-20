package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Table or view entry returned by metadata listing.")
public record TableEntry(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Table-like object type, such as TABLE, VIEW, or MATERIALIZED VIEW.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String remarks
) {
    public TableEntry(String schema, String name, String type) {
        this(schema, name, type, null);
    }
}
