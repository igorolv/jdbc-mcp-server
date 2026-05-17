package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QueryOrderBy response payload.")
public record QueryOrderBy(
        @Schema(description = "Expression.", nullable = true)
        String expression,
        @Schema(description = "Source.", nullable = true)
        String source,
        @Schema(description = "Columns.", nullable = true)
        List<QueryColumnRef> columns
) {
}
