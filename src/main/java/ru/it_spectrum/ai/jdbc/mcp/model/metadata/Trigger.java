package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Trigger metadata for a table; full body is present only when requested or available.")
public record Trigger(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Trigger timing such as BEFORE, AFTER, or INSTEAD OF.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String timing,
        @Schema(description = "Data-change events that fire the trigger, such as INSERT, UPDATE, or DELETE.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> events,
        @Schema(description = "True when the trigger is currently enabled.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean enabled,
        @Schema(description = "Trigger body or definition text when loaded; compact listings may omit it.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String definition
) {
}
