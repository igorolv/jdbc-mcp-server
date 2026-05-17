package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QueryPredicate response payload.")
public record QueryPredicate(
        @Schema(description = "Scope.", nullable = true)
        String scope,
        @Schema(description = "Expression.", nullable = true)
        String expression,
        @Schema(description = "Operator.", nullable = true)
        String operator,
        @Schema(description = "Columns.", nullable = true)
        List<QueryColumnRef> columns
) {
}
