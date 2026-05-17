package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QueryFeatures response payload.")
public record QueryFeatures(
        @Schema(description = "Select Star.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean selectStar,
        @Schema(description = "Set Operation.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean setOperation,
        @Schema(description = "Group By.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean groupBy,
        @Schema(description = "Limit Or Fetch.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean limitOrFetch,
        @Schema(description = "Offset.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean offset,
        @Schema(description = "Select Into.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean selectInto,
        @Schema(description = "For Update.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean forUpdate,
        @Schema(description = "Functions.", nullable = true)
        List<String> functions
) {
}
