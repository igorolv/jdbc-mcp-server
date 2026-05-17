package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ListQueriesResult response payload.")
public record ListQueriesResult(
        @Schema(description = "Queries.", nullable = true)
        List<QueryEntry> queries,
        @Schema(description = "Limit.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int limit,
        @Schema(description = "Offset.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int offset,
        @Schema(description = "Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count
) {
    @Schema(description = "QueryEntry response payload.")
    public record QueryEntry(
            @Schema(description = "Source Kind.", nullable = true)
            String sourceKind,
            @Schema(description = "Source Path.", nullable = true)
            String sourcePath,
            @Schema(description = "Source Unit.", nullable = true)
            String sourceUnit,
            @Schema(description = "Business Label.", nullable = true)
            String businessLabel,
            @Schema(description = "Business Domain.", nullable = true)
            String businessDomain,
            @Schema(description = "Parse Status.", nullable = true)
            String parseStatus
    ) {
    }
}