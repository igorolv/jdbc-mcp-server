package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QuerySelectItem response payload.")
public record QuerySelectItem(
        @Schema(description = "Expression.", nullable = true)
        String expression,
        @Schema(description = "Alias.", nullable = true)
        String alias,
        @Schema(description = "Star.", nullable = true)
        Boolean star,
        @Schema(description = "Columns.", nullable = true)
        List<QueryColumnRef> columns
) {
}
