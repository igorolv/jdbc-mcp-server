package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryInspection;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryParameter;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryTableRef;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryWarning;

import static org.assertj.core.api.Assertions.assertThat;

class QueryAnalysisServiceTest {

    private final QueryAnalysisService analysis = new QueryAnalysisService();

    @Test
    void extractsTablesAliasesJoinsPredicatesAndParameters() {
        QueryInspection result = analysis.inspect("""
                SELECT c.name, COUNT(o.id) AS orders_count
                FROM customers c
                LEFT JOIN orders o ON o.customer_id = c.id
                WHERE c.status = :status AND c.email LIKE '%@example.com'
                GROUP BY c.name
                ORDER BY c.name
                """);

        assertThat(result.parseable()).isEqualTo(true);
        assertThat(result.statementType()).isEqualTo("PlainSelect");
        assertThat(result.tables())
                .extracting(QueryTableRef::name)
                .contains("customers", "orders");
        assertThat(result.aliases()).containsEntry("c", "customers").containsEntry("o", "orders");
        assertThat(result.joins()).hasSize(1);
        assertThat(result.predicates()).hasSize(2);
        assertThat(result.parameters())
                .extracting(QueryParameter::name)
                .contains("status");
        assertThat(result.warnings())
                .extracting(QueryWarning::code)
                .contains("leading_wildcard_like");
    }

    @Test
    void reportsParserErrorsWithoutThrowing() {
        QueryInspection result = analysis.inspect("SELECT FROM");

        assertThat(result.parseable()).isEqualTo(false);
        assertThat(result.error()).isNotNull();
    }
}
