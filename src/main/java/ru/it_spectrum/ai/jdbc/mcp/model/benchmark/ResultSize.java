package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Shape and truncation status of a query result without carrying the row payload itself.")
public record ResultSize(
        @Schema(description = "Number of rows returned or represented in this response.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int rowCount,
        @Schema(description = "True when the configured row or finding cap was reached and more data may exist.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean truncated,
        @Schema(description = "Result column names in output order.", nullable = true)
        List<String> columns,
        @Schema(description = "Database type names for the result columns, in the same order as columns.", nullable = true)
        List<String> columnTypes
) {
}
