package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "TimedQueryResult response payload.")
public record TimedQueryResult(
        @Schema(description = "Engine.", nullable = true)
        String engine,
        @Schema(description = "Elapsed Ms.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double elapsedMs,
        @Schema(description = "Row Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int rowCount,
        @Schema(description = "Truncated.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean truncated,
        @Schema(description = "Columns.", nullable = true)
        List<String> columns,
        @Schema(description = "Column Types.", nullable = true)
        List<String> columnTypes,
        @Schema(description = "Rows.", nullable = true)
        List<Map<String, Object>> rows,
        @Schema(description = "Pg Stat Statements.", nullable = true)
        PgStatStatements pgStatStatements
) {
}