package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Per-column null ratio report for a table.")
public record NullRatio(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Total number of rows considered for this statistic.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long totalRows,
        @Schema(description = "Per-column null-ratio entries, sorted by descending null ratio.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<ColumnEntry> columns
) {
    @Schema(description = "Null ratio details for one column.")
    public record ColumnEntry(
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String column,
            @Schema(description = "Number of rows where the column value is not NULL.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long nonNullRows,
            @Schema(description = "Number of rows where the column value is NULL.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long nullRows,
            @Schema(description = "Share of rows where the column value is NULL, from 0.0 to 1.0.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            double nullRatio,
            @Schema(description = "True when more than half of the column values are NULL; partial indexes may be useful.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean sparse
    ) {}
}
