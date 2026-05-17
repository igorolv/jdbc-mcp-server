package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "Parsed SQL authoring summary: statement type, tables, aliases, CTEs, joins, predicates, columns, parameters, features, and warnings.")
public record QueryInspection(
        @Schema(description = "True when SQL parsing succeeded well enough to produce structured inspection data.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean parseable,
        @Schema(description = "Human-readable error message explaining why the requested operation failed.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error,
        @Schema(description = "Top-level SQL statement type detected by the parser.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String statementType,
        @Schema(description = "True when the inspected statement is an EXPLAIN statement.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean explain,
        @Schema(description = "Table references extracted from the query, including CTEs and subqueries where possible.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryTableRef> tables,
        @Schema(description = "Map of SQL alias to referenced table or expression.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Map<String, String> aliases,
        @Schema(description = "Common table expression names declared by the query.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> cteNames,
        @Schema(description = "Expressions in the SELECT list, in output order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QuerySelectItem> selectItems,
        @Schema(description = "Join clauses detected in the query.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryJoin> joins,
        @Schema(description = "Predicate expressions extracted from WHERE, JOIN, HAVING, and related scopes.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryPredicate> predicates,
        @Schema(description = "ORDER BY expressions in the query.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryOrderBy> orderBy,
        @Schema(description = "Column references extracted from all inspectable SQL clauses.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryColumnRef> columns,
        @Schema(description = "SQL placeholders or documented query parameters.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryParameter> parameters,
        @Schema(description = "Detected SQL feature flags useful for authoring and linting.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        QueryFeatures features,
        @Schema(description = "Warnings produced while parsing and inspecting the query.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryWarning> warnings,
        @Schema(description = "Normalized SQL text produced by parser or usage-catalog indexing.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String normalizedSql
) {
    public static QueryInspection error(String error) {
        return new QueryInspection(
                false, error, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
