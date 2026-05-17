package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ORDER BY expression extracted from parsed SQL.")
public record QueryOrderBy(
        @Schema(description = "SQL expression text for the parsed item.", nullable = true)
        String expression,
        @Schema(description = "ORDER BY source text or clause location.", nullable = true)
        String source,
        @Schema(description = "Column references used by this ORDER BY expression.", nullable = true)
        List<QueryColumnRef> columns
) {
}
