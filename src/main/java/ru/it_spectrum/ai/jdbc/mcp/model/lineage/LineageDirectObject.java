package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Object directly referenced by the parsed query before recursive view or routine expansion.")
public record LineageDirectObject(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "SQL alias assigned to the directly referenced object, when present.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String alias,
        @Schema(description = "SQL source location where the object was referenced directly.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String source,
        @Schema(description = "Whether the parser or resolver matched the reference to a database object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String resolutionStatus
) {
}
