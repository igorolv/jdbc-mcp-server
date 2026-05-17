package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Lightweight database object reference used inside lineage responses.")
public record LineageObjectRef(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Database object type, SQL construct type, or engine-specific classification.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type
) {
}
