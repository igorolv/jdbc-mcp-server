package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Boolean feature flags and function calls detected while inspecting SQL.")
public record QueryFeatures(
        @Schema(description = "True when the query uses SELECT * or equivalent wildcard projection.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean selectStar,
        @Schema(description = "True when the query uses UNION, INTERSECT, EXCEPT, or another set operation.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean setOperation,
        @Schema(description = "True when the query contains a GROUP BY clause.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean groupBy,
        @Schema(description = "True when the query contains LIMIT, FETCH FIRST, TOP, or equivalent row limiting.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean limitOrFetch,
        @Schema(description = "Zero-based result offset for pagination, or true when SQL contains OFFSET depending on context.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean offset,
        @Schema(description = "True when the inspected SQL contains SELECT INTO.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean selectInto,
        @Schema(description = "True when the inspected SQL contains FOR UPDATE or a locking clause.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean forUpdate,
        @Schema(description = "Function calls detected in the SQL expression tree.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> functions
) {
}
