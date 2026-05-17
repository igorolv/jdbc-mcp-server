package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;

@Schema(description = "Schema lint audit result with capped findings for modeling, indexing, and data-quality issues.")
public record SchemaLint(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true)
        String table,
        @Schema(description = "Schema lint checks that were enabled for this audit.", nullable = true)
        Set<String> checks,
        @Schema(description = "Number of tables inspected by the tool before caps were applied.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tablesScanned,
        @Schema(description = "Number of lint findings returned or counted.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int findingCount,
        @Schema(description = "True when the configured row or finding cap was reached and more data may exist.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean truncated,
        @Schema(description = "Schema lint or redundant-index findings.", nullable = true)
        List<SchemaLintFinding> findings
) {}
