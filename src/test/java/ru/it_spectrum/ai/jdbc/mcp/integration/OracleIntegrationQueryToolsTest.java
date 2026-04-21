package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OracleIntegrationQueryToolsTest extends AbstractOracleToolsIntegrationTest {

    @Test
    void executeQuerySupportsJsonLimitAndNamedParams() {
        ObjectNode result = object(queryTools().executeQuery(
                "SELECT name, email FROM customers ORDER BY id",
                null, null, 1, 5, null));

        assertThat(field(result, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(result, "truncated").asBoolean()).isTrue();
        assertThat(field(row(result, 0), "name").asText()).isEqualTo("Alice");

        String csv = queryTools().executeQuery(
                "SELECT COUNT(*) AS c FROM orders WHERE customer_id = :customerId",
                null, Map.of("customerId", 1), null, 5, "csv");
        assertThat(csv).contains("C").contains("2");
    }

    @Test
    void explainAnalyzeAndValidateReturnToolLevelResponses() {
        String plan = queryTools().explainQuery(
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, false);
        assertThat(plan).containsIgnoringCase("CUSTOMERS");

        ObjectNode summary = object(queryTools().analyzePlan(
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("oracle");
        assertThat(field(summary, "node_count").asInt()).isGreaterThan(0);

        String valid = queryTools().validateQuery("SELECT * FROM customers", null, null);
        assertThat(valid).startsWith("VALID.");
        String validNamed = queryTools().validateQuery(
                "SELECT COUNT(*) FROM orders WHERE status = :status",
                null, Map.of("status", "PAID"));
        assertThat(validNamed).startsWith("VALID.");

        assertThat(queryTools().validateQuery("DELETE FROM customers", null, null))
                .startsWith("INVALID (guard):");
    }

    @Test
    void queryToolsRejectWrites() {
        assertRejected(
                queryTools().executeQuery("DELETE FROM customers", null, null, null, null, null),
                "Only SELECT");
    }
}
