package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QueryJoin response payload.")
public record QueryJoin(
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Right Item.", nullable = true)
        String rightItem,
        @Schema(description = "On.", nullable = true)
        String on,
        @Schema(description = "Using.", nullable = true)
        List<String> using,
        @Schema(description = "Conditionless.", nullable = true)
        Boolean conditionless
) {
}
