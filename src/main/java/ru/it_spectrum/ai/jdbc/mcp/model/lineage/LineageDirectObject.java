package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Object directly referenced by the parsed query before recursive view or routine expansion.")
public record LineageDirectObject(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Database object type, SQL construct type, or engine-specific classification.", nullable = true)
        String type,
        @Schema(description = "SQL alias assigned to the directly referenced object, when present.", nullable = true)
        String alias,
        @Schema(description = "SQL source location where the object was referenced directly.", nullable = true)
        String source,
        @Schema(description = "Whether the parser or resolver matched the reference to a database object.", nullable = true)
        String resolutionStatus
) {
}
