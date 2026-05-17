package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Table-like reference extracted from parsed SQL, including aliases and CTE sources.")
public record QueryTableRef(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Schema-qualified or original full table name as it appeared in SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fullName,
        @Schema(description = "SQL alias assigned to the table reference in the inspected query.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String alias,
        @Schema(description = "SQL construct that introduced the table reference, such as FROM, JOIN, CTE, or subquery.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String source
) {
}
