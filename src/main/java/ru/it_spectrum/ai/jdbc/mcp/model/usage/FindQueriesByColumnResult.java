package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "FindQueriesByColumnResult response payload.")
public record FindQueriesByColumnResult(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Column.", nullable = true)
        String column,
        @Schema(description = "Matches.", nullable = true)
        List<Match> matches,
        @Schema(description = "Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count
) {
    @Schema(description = "Match response payload.")
    public record Match(
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
            @Schema(description = "Context.", nullable = true)
            String context,
            @Schema(description = "Schema Resolved.", nullable = true)
            String schemaResolved,
            @Schema(description = "Table Resolved.", nullable = true)
            String tableResolved,
            @Schema(description = "Column Name.", nullable = true)
            String columnName
    ) {
    }
}