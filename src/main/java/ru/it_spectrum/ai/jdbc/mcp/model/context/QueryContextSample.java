package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "Small sample result attached to query context when sample rows are requested.")
public record QueryContextSample(
        @Schema(description = "Sample column names in output order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> columns,
        @Schema(description = "Sample rows as column-name to value maps, intended only to show data shape.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Map<String, Object>> rows,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer rowCount,
        @Schema(description = "Error captured while trying to fetch sample rows; rows may be absent when set.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String sampleError
) {
}
