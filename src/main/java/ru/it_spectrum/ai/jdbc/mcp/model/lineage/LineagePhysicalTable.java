package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Physical table reached after expanding views and routines in query lineage.")
public record LineagePhysicalTable(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Database object type, SQL construct type, or engine-specific classification.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "Expansion path showing how this object was reached from the original query reference.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> via,
        @Schema(description = "Relationship expansion depth from the root object.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int depth
) {
}
