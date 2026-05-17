package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Table or view entry returned by metadata listing.")
public record TableEntry(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Table-like object type, such as TABLE, VIEW, or MATERIALIZED VIEW.", nullable = true)
        String type,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true)
        String remarks
) {
    public TableEntry(String schema, String name, String type) {
        this(schema, name, type, null);
    }
}
