package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationQueryToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void executeQuerySupportsJsonLimitAndNamedParams() {
        ObjectNode result = object(queryTools().executeQuery(
                "SELECT name, email FROM customers ORDER BY id",
                null, null, 1, 5));

        assertThat(field(result, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(result, "truncated").asBoolean()).isTrue();
        assertThat(field(row(result, 0), "name").asText()).isEqualTo("Alice");

        ObjectNode count = object(queryTools().executeQuery(
                "SELECT COUNT(*) AS c FROM orders WHERE customer_id = :customerId",
                null, Map.of("customerId", 1), null, 5));
        assertThat(field(row(count, 0), "c").asInt()).isEqualTo(2);
    }

    @Test
    void explainAnalyzeAndValidateReturnToolLevelResponses() {
        String plan = queryTools().explainQuery(
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, false);
        assertThat(plan).containsIgnoringCase("customers");

        ObjectNode summary = object(queryTools().analyzePlan(
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("postgresql");
        assertThat(field(summary, "nodeCount").asInt()).isGreaterThan(0);

        ObjectNode valid = object(queryTools().validateQuery("SELECT * FROM customers", null, null));
        assertThat(field(valid, "valid").asBoolean()).isTrue();
        assertThat(field(field(valid, "inspection"), "parseable").asBoolean()).isTrue();
        ObjectNode validNamed = object(queryTools().validateQuery(
                "SELECT COUNT(*) FROM events WHERE status = :status",
                null, Map.of("status", "PAID")));
        assertThat(field(validNamed, "valid").asBoolean()).isTrue();

        assertInvalidArgument(() ->
                queryTools().executeQuery(
                        "SELECT * FROM customers WHERE id = ?",
                        null, null, 5, 5),
                "contains '?' placeholders");
        assertInvalidArgument(() ->
                queryTools().executeQuery(
                        "SELECT * FROM customers WHERE id = :id",
                        java.util.List.of(1), null, 5, 5),
                "'namedParams'");

        ObjectNode invalid = object(queryTools().validateQuery("DELETE FROM customers", null, null));
        assertThat(field(invalid, "valid").asBoolean()).isFalse();
        assertThat(field(invalid, "stage").asText()).isEqualTo("guard");
        assertThat(field(invalid, "inspection")).isNotNull();
    }

    @Test
    void queryToolsRejectWrites() {
        assertRejected(() ->
                queryTools().executeQuery("DELETE FROM customers", null, null, null, null),
                "Only SELECT");
    }

    @Test
    void inspectQueryAndQueryLintReturnAuthoringSignals() {
        ObjectNode inspection = object(queryTools().inspectQuery("""
                SELECT c.name, o.total
                FROM customers c
                JOIN orders o ON o.customer_id = c.id
                WHERE c.email LIKE '%@example.com'
                ORDER BY o.total
                """));
        assertThat(field(inspection, "parseable").asBoolean()).isTrue();
        assertThat(field(inspection, "tables").size()).isEqualTo(2);
        assertThat(field(inspection, "predicates").size()).isEqualTo(1);

        ObjectNode lint = object(queryTools().queryLint("""
                SELECT *
                FROM customers c
                JOIN orders o ON o.customer_id = c.id
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
