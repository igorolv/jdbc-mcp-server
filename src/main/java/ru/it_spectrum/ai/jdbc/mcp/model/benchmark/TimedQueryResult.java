package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "Query execution result with wall-clock elapsed time and optional pg_stat_statements deltas for PostgreSQL.")
public record TimedQueryResult(
        @Schema(description = "Database engine that produced the result, such as PostgreSQL, Oracle, or SQL Server.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String engine,
        @Schema(description = "Wall-clock elapsed time for the query execution, in milliseconds.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double elapsedMs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int rowCount,
        @Schema(description = "True when the configured row or finding cap was reached and more data may exist.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean truncated,
        @Schema(description = "Result column names in output order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> columns,
        @Schema(description = "Database type names for the result columns, in the same order as columns.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> columnTypes,
        @Schema(description = "Returned result rows as column-name to value maps, capped by the requested limit.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Map<String, Object>> rows,
        @Schema(description = "PostgreSQL statement-statistics delta captured around the timed query, when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        PgStatStatements pgStatStatements
) {
}
