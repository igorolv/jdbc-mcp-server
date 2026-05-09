package ru.it_spectrum.ai.jdbc.mcp.model.query;

import java.util.List;

public record QueryLintResult(
        QueryInspection inspection,
        boolean lintable,
        List<CheckedTableSummary> tablesChecked,
        Integer warningCount,
        List<QueryWarning> warnings
) {
    public static QueryLintResult parserError(QueryInspection inspection, QueryWarning warning) {
        return new QueryLintResult(inspection, false, null, null, List.of(warning));
    }
}
