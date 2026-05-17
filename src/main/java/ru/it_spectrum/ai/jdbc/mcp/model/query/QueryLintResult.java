package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QueryLintResult response payload.")
public record QueryLintResult(
        @Schema(description = "Inspection.", nullable = true)
        QueryInspection inspection,
        @Schema(description = "Lintable.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean lintable,
        @Schema(description = "Tables Checked.", nullable = true)
        List<CheckedTableSummary> tablesChecked,
        @Schema(description = "Warning Count.", nullable = true)
        Integer warningCount,
        @Schema(description = "Warnings.", nullable = true)
        List<QueryWarning> warnings
) {
    public static QueryLintResult parserError(QueryInspection inspection, QueryWarning warning) {
        return new QueryLintResult(inspection, false, null, null, List.of(warning));
    }
}
