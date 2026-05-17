package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "QueryInspection response payload.")
public record QueryInspection(
        @Schema(description = "Parseable.", nullable = true)
        Boolean parseable,
        @Schema(description = "Error.", nullable = true)
        String error,
        @Schema(description = "Statement Type.", nullable = true)
        String statementType,
        @Schema(description = "Explain.", nullable = true)
        Boolean explain,
        @Schema(description = "Tables.", nullable = true)
        List<QueryTableRef> tables,
        @Schema(description = "Aliases.", nullable = true)
        Map<String, String> aliases,
        @Schema(description = "Cte Names.", nullable = true)
        List<String> cteNames,
        @Schema(description = "Select Items.", nullable = true)
        List<QuerySelectItem> selectItems,
        @Schema(description = "Joins.", nullable = true)
        List<QueryJoin> joins,
        @Schema(description = "Predicates.", nullable = true)
        List<QueryPredicate> predicates,
        @Schema(description = "Order By.", nullable = true)
        List<QueryOrderBy> orderBy,
        @Schema(description = "Columns.", nullable = true)
        List<QueryColumnRef> columns,
        @Schema(description = "Parameters.", nullable = true)
        List<QueryParameter> parameters,
        @Schema(description = "Features.", nullable = true)
        QueryFeatures features,
        @Schema(description = "Warnings.", nullable = true)
        List<QueryWarning> warnings,
        @Schema(description = "Normalized Sql.", nullable = true)
        String normalizedSql
) {
    public static QueryInspection error(String error) {
        return new QueryInspection(
                false, error, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
