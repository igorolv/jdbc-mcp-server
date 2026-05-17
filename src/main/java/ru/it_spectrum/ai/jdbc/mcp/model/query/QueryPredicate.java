package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Predicate expression extracted from WHERE, JOIN, HAVING, or related SQL scopes.")
public record QueryPredicate(
        @Schema(description = "SQL clause or expression scope where the predicate was found.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String scope,
        @Schema(description = "SQL expression text for the parsed item.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String expression,
        @Schema(description = "Primary comparison or logical operator detected in the predicate.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String operator,
        @Schema(description = "Column references used by this predicate expression.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryColumnRef> columns
) {
}
