package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Usage-catalog matches for known SQL queries that reference a specific column.")
public record FindQueriesByColumnResult(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table containing the searched column.", nullable = true)
        String table,
        @Schema(description = "Column name within the table.", nullable = true)
        String column,
        @Schema(description = "Usage-catalog matches returned for the requested table, column, or filter.", nullable = true)
        List<Match> matches,
        @Schema(description = "Number of matching catalog queries returned for the column.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count
) {
    @Schema(description = "Usage-catalog match showing where a table or column was referenced and with what business context.")
    public record Match(
            @Schema(description = "Kind of source that produced the catalog query, such as file, view, routine, or configured import.", nullable = true)
            String sourceKind,
            @Schema(description = "Path or database object name where the catalog query came from.", nullable = true)
            String sourcePath,
            @Schema(description = "Stable unit identifier inside the source, such as query id, method name, view name, or routine name.", nullable = true)
            String sourceUnit,
            @Schema(description = "Human-readable business label attached to the query, parameter, or output.", nullable = true)
            String businessLabel,
            @Schema(description = "Business domain assigned to the catalog query or usage record.", nullable = true)
            String businessDomain,
            @Schema(description = "Usage context for the column reference, such as select, where, join, order_by, or having.", nullable = true)
            String context,
            @Schema(description = "Schema resolved by parser and metadata matching.", nullable = true)
            String schemaResolved,
            @Schema(description = "Table name resolved by parser and metadata matching.", nullable = true)
            String tableResolved,
            @Schema(description = "Resolved column name referenced by the query.", nullable = true)
            String columnName
    ) {
    }
}
