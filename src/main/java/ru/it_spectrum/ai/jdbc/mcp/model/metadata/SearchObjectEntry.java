package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Database object found by cross-object metadata search.")
public record SearchObjectEntry(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Database object type returned by search, such as TABLE, VIEW, ROUTINE, SEQUENCE, or SYNONYM.", nullable = true)
        String type
) {
}