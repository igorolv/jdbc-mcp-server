package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryAnalysisServiceTest {

    private final QueryAnalysisService analysis = new QueryAnalysisService();

    @Test
    void extractsTablesAliasesJoinsPredicatesAndParameters() {
        Map<String, Object> result = analysis.inspect("""
                SELECT c.name, COUNT(o.id) AS orders_count
                FROM customers c
                LEFT JOIN orders o ON o.customer_id = c.id
                WHERE c.status = :status AND c.email LIKE '%@example.com'
                GROUP BY c.name
                ORDER BY c.name
                """);

        assertThat(result.get("parseable")).isEqualTo(true);
        assertThat(result.get("statementType")).isEqualTo("PlainSelect");
        assertThat(asList(result, "tables"))
                .extracting(row -> row.get("name"))
                .contains("customers", "orders");
        assertThat(asMap(result, "aliases")).containsEntry("c", "customers").containsEntry("o", "orders");
        assertThat(asList(result, "joins")).hasSize(1);
        assertThat(asList(result, "predicates")).hasSize(2);
        assertThat(asList(result, "parameters"))
                .extracting(row -> row.get("name"))
                .contains("status");
        assertThat(asList(result, "warnings"))
                .extracting(row -> row.get("code"))
                .contains("leading_wildcard_like");
    }

    @Test
    void reportsParserErrorsWithoutThrowing() {
        Map<String, Object> result = analysis.inspect("SELECT FROM");

        assertThat(result.get("parseable")).isEqualTo(false);
        assertThat(result.get("error")).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Map<String, Object> row, String key) {
        return (List<Map<String, Object>>) row.get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> asMap(Map<String, Object> row, String key) {
        return (Map<String, String>) row.get(key);
    }
}
