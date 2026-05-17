package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;

@Schema(description = "SchemaLint response payload.")
public record SchemaLint(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Checks.", nullable = true)
        Set<String> checks,
        @Schema(description = "Tables Scanned.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tablesScanned,
        @Schema(description = "Finding Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int findingCount,
        @Schema(description = "Truncated.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean truncated,
        @Schema(description = "Findings.", nullable = true)
        List<SchemaLintFinding> findings
) {}
