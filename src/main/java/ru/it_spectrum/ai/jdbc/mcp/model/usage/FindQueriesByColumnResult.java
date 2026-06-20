package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Usage-catalog matches for known SQL queries that reference a specific column.")
public record FindQueriesByColumnResult(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Table containing the searched column.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String column,
        @Schema(description = "Usage-catalog matches returned for the requested table, column, or filter.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Match> matches,
        @Schema(description = "Number of matching catalog queries returned for the column.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count
) {
    @Schema(description = "Usage-catalog match showing where a table or column was referenced and with what business context.")
    public record Match(
            @Schema(description = "Kind of source that produced the catalog query, such as file, view, routine, or configured import.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String sourceKind,
            @Schema(description = "Path or database object name where the catalog query came from.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String sourcePath,
            @Schema(description = "Stable unit identifier inside the source, such as query id, method name, view name, or routine name.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String sourceUnit,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String businessLabel,
            @Schema(description = "Business domain assigned to the catalog query or usage record.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String businessDomain,
            @Schema(description = "Usage context for the column reference, such as select, where, join, order_by, or having.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String context,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String schemaResolved,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String tableResolved,
            @Schema(description = "Resolved column name referenced by the query.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String columnName
    ) {
    }
}
