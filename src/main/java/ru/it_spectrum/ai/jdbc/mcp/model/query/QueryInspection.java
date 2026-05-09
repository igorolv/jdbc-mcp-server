package ru.it_spectrum.ai.jdbc.mcp.model.query;

import java.util.List;
import java.util.Map;

public record QueryInspection(
        Boolean parseable,
        String error,
        String statementType,
        Boolean explain,
        List<QueryTableRef> tables,
        Map<String, String> aliases,
        List<String> cteNames,
        List<QuerySelectItem> selectItems,
        List<QueryJoin> joins,
        List<QueryPredicate> predicates,
        List<QueryOrderBy> orderBy,
        List<QueryColumnRef> columns,
        List<QueryParameter> parameters,
        QueryFeatures features,
        List<QueryWarning> warnings,
        String normalizedSql
) {
    public static QueryInspection error(String error) {
        return new QueryInspection(
                false, error, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
