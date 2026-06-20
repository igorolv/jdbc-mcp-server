package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Usage-catalog matches for known SQL queries that reference a specific table.")
public record FindQueriesByTableResult(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Table searched in the usage catalog.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Usage-catalog matches returned for the requested table, column, or filter.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Match> matches,
        @Schema(description = "Number of matching catalog queries returned for the table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
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
            @Schema(description = "Role of the table reference in SQL, such as from, join, cte, or subquery.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String role,
            @Schema(description = "SQL alias used for the table, select item, or output field.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String alias,
            @Schema(description = "Raw table name as it appeared in the SQL source before resolution.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String rawName,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String schemaResolved,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String tableResolved
    ) {
    }
}