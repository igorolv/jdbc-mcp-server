package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "NullRatio response payload.")
public record NullRatio(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Total Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long totalRows,
        @Schema(description = "Columns.", nullable = true)
        List<ColumnEntry> columns
) {
    @Schema(description = "ColumnEntry response payload.")
    public record ColumnEntry(
            @Schema(description = "Column.", nullable = true)
            String column,
            @Schema(description = "Non Null Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long nonNullRows,
            @Schema(description = "Null Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long nullRows,
            @Schema(description = "Null Ratio.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            double nullRatio,
            @Schema(description = "Sparse.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean sparse
    ) {}
}
