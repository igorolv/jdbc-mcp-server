package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "FindQueriesByTableResult response payload.")
public record FindQueriesByTableResult(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
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
            @Schema(description = "Role.", nullable = true)
            String role,
            @Schema(description = "Alias.", nullable = true)
            String alias,
            @Schema(description = "Raw Name.", nullable = true)
            String rawName,
            @Schema(description = "Schema Resolved.", nullable = true)
            String schemaResolved,
            @Schema(description = "Table Resolved.", nullable = true)
            String tableResolved
    ) {
    }
}