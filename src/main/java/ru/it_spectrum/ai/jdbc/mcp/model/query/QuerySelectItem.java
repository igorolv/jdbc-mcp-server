package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "SELECT-list expression extracted from parsed SQL.")
public record QuerySelectItem(
        @Schema(description = "SQL expression text for the parsed item.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String expression,
        @Schema(description = "Alias assigned to the SELECT expression, when present.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String alias,
        @Schema(description = "True when the SELECT item is a wildcard projection.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean star,
        @Schema(description = "Column references used by this SELECT expression.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryColumnRef> columns
) {
}
