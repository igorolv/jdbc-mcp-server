package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ResultSize response payload.")
public record ResultSize(
        @Schema(description = "Row Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int rowCount,
        @Schema(description = "Truncated.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean truncated,
        @Schema(description = "Columns.", nullable = true)
        List<String> columns,
        @Schema(description = "Column Types.", nullable = true)
        List<String> columnTypes
) {
}