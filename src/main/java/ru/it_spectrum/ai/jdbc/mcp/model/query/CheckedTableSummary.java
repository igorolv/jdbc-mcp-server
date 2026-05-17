package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CheckedTableSummary response payload.")
public record CheckedTableSummary(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Exists.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean exists,
        @Schema(description = "Column Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int columnCount,
        @Schema(description = "Index Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int indexCount
) {
}
