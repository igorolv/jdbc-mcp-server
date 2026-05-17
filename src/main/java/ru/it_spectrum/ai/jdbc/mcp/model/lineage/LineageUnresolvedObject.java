package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "LineageUnresolvedObject response payload.")
public record LineageUnresolvedObject(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Kind.", nullable = true)
        String kind,
        @Schema(description = "Source.", nullable = true)
        String source,
        @Schema(description = "Reason.", nullable = true)
        String reason,
        @Schema(description = "Candidates.", nullable = true)
        List<LineageObjectRef> candidates,
        @Schema(description = "Via.", nullable = true)
        List<String> via
) {
}
