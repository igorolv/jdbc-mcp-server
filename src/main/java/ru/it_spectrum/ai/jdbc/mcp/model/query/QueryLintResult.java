package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Query lint result that combines SQL inspection with metadata checks.")
public record QueryLintResult(
        @Schema(description = "Parsed query inspection that underpins validation, lint, or lineage results.", nullable = true)
        QueryInspection inspection,
        @Schema(description = "True when query lint could combine parsed SQL with metadata checks.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean lintable,
        @Schema(description = "Tables whose metadata was checked during query lint.", nullable = true)
        List<CheckedTableSummary> tablesChecked,
        @Schema(description = "Number of warnings produced by inspection or lint.", nullable = true)
        Integer warningCount,
        @Schema(description = "Warnings produced by SQL inspection and metadata-aware lint checks.", nullable = true)
        List<QueryWarning> warnings
) {
    public static QueryLintResult parserError(QueryInspection inspection, QueryWarning warning) {
        return new QueryLintResult(inspection, false, null, null, List.of(warning));
    }
}
