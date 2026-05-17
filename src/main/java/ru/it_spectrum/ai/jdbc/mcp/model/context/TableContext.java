package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Neighborhood around a root table, including related tables and relationship edges.")
public record TableContext(
        @Schema(description = "Schema of the root table requested for table context.", nullable = true)
        String rootSchema,
        @Schema(description = "Root table requested for table context.", nullable = true)
        String rootTable,
        @Schema(description = "Relationship expansion depth from the root object.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int depth,
        @Schema(description = "True when tables that reference the root table were included.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeIncoming,
        @Schema(description = "True when live table statistics were requested for context tables.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeStats,
        @Schema(description = "True when usage-catalog observed joins were included as relationship evidence.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean includeObserved,
        @Schema(description = "Tables included in this context, graph, query inspection, or usage record.", nullable = true)
        List<ContextTable> tables,
        @Schema(description = "Relationship edges relevant to the context, graph, or observed-relationships result.", nullable = true)
        List<RelationshipEdge> relationships
) {}
