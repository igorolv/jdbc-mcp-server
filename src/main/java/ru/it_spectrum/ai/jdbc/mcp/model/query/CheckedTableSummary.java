package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Metadata lookup summary for a table referenced by query lint.")
public record CheckedTableSummary(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "True when metadata lookup found the referenced table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean exists,
        @Schema(description = "Number of columns visible for the table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int columnCount,
        @Schema(description = "Number of visible indexes found for the table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int indexCount
) {
}
