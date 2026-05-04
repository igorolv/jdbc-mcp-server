package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class SqlServerIntegrationQueryToolsTest extends AbstractSqlServerToolsIntegrationTest {

    @Test
    void executeQuerySupportsJsonLimitAndNamedParams() {
        ObjectNode result = object(queryTools().executeQuery(
                "SELECT name, email FROM dbo.customers ORDER BY id",
                null, null, 1, 5, null));

        assertThat(field(result, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(result, "truncated").asBoolean()).isTrue();
        assertThat(field(row(result, 0), "name").asText()).isEqualTo("Alice");

        String csv = queryTools().executeQuery(
                "SELECT COUNT(*) AS c FROM dbo.orders WHERE customer_id = :customerId",
                null, Map.of("customerId", 1), null, 5, "csv");
        assertThat(csv).contains("c").contains("2");
    }

    @Test
    void explainAnalyzeAndValidateReturnToolLevelResponses() {
        String plan = queryTools().explainQuery(
                "SELECT * FROM dbo.customers WHERE name LIKE 'A%'", null, null, false);
        assertThat(plan).containsIgnoringCase("customers");

        ObjectNode summary = object(queryTools().analyzePlan(
                "SELECT * FROM dbo.customers WHERE name LIKE 'A%'", null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("mssql");
        assertThat(field(summary, "node_count").asInt()).isGreaterThan(0);

        ObjectNode valid = object(queryTools().validateQuery("SELECT * FROM dbo.customers", null, null));
        assertThat(field(valid, "valid").asBoolean()).isTrue();
        assertThat(field(field(valid, "inspection"), "parseable").asBoolean()).isTrue();
    }

    @Test
    void queryToolsRejectWrites() {
        assertRejected(
                queryTools().executeQuery("DELETE FROM dbo.customers", null, null, null, null, null),
                "Only SELECT");
    }

    @Test
    void inspectQueryAndQueryLintReturnAuthoringSignals() {
        ObjectNode inspection = object(queryTools().inspectQuery("""
                SELECT c.name, o.total
                FROM dbo.customers c
                JOIN dbo.orders o ON o.customer_id = c.id
                WHERE c.email LIKE '%@example.com'
                ORDER BY o.total
                """));
        assertThat(field(inspection, "parseable").asBoolean()).isTrue();
        assertThat(field(inspection, "tables").size()).isEqualTo(2);

        ObjectNode lint = object(queryTools().queryLint("""
                SELECT *
                FROM dbo.customers c
                JOIN dbo.orders o ON o.customer_id = c.id
                WHERE c.email LIKE '%@example.com'
                ORDER BY o.total
                """, schema()));
        assertThat(field(lint, "lintable").asBoolean()).isTrue();
        assertThat(field(lint, "warningCount").asInt()).isGreaterThan(0);
        assertThat(field(lint, "warnings").toString())
                .contains("select_star")
                .contains("leading_wildcard_like");
    }
}
