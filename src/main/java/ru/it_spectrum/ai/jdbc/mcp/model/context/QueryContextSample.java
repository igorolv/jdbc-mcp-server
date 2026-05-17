package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "QueryContextSample response payload.")
public record QueryContextSample(
        @Schema(description = "Columns.", nullable = true)
        List<String> columns,
        @Schema(description = "Rows.", nullable = true)
        List<Map<String, Object>> rows,
        @Schema(description = "Row Count.", nullable = true)
        Integer rowCount,
        @Schema(description = "Sample Error.", nullable = true)
        String sampleError
) {
}
