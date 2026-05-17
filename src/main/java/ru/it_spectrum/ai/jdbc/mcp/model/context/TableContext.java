package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "TableContext response payload.")
public record TableContext(
        @Schema(description = "Root Schema.", nullable = true)
        String rootSchema,
        @Schema(description = "Root Table.", nullable = true)
        String rootTable,
        @Schema(description = "Depth.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int depth,
        @Schema(description = "Include Incoming.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeIncoming,
        @Schema(description = "Include Stats.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeStats,
        @Schema(description = "Include Observed.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeObserved,
        @Schema(description = "Tables.", nullable = true)
        List<ContextTable> tables,
        @Schema(description = "Relationships.", nullable = true)
        List<RelationshipEdge> relationships
) {}
