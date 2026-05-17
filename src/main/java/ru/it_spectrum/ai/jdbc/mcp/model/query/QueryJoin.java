package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Join clause extracted from parsed SQL.")
public record QueryJoin(
        @Schema(description = "Join type detected in SQL, such as INNER, LEFT, RIGHT, FULL, CROSS, or implicit.", nullable = true)
        String type,
        @Schema(description = "Right-hand table or subquery item in the join clause.", nullable = true)
        String rightItem,
        @Schema(description = "ON expression text for the join clause.", nullable = true)
        String on,
        @Schema(description = "Columns listed in a USING join clause.", nullable = true)
        List<String> using,
        @Schema(description = "True when the join lacks an ON or USING condition and may be a Cartesian join.", nullable = true)
        Boolean conditionless
) {
}
