package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ObjectNode;
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
                null, null, 1, 5));

        assertThat(field(result, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(result, "truncated").asBoolean()).isTrue();
        assertThat(field(row(result, 0), "name").asText()).isEqualTo("Alice");

        ObjectNode count = object(queryTools().executeQuery(
                "SELECT COUNT(*) AS c FROM orders WHERE customer_id = :customerId",
                null, Map.of("customerId", 1), null, 5));
        assertThat(field(row(count, 0), "C").asInt()).isEqualTo(2);
    }

    @Test
    void explainAnalyzeAndValidateReturnToolLevelResponses() {
        String plan = queryAnalysisTools().explainQuery(
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, false);
        assertThat(plan).containsIgnoringCase("CUSTOMERS");

        ObjectNode summary = object(queryAnalysisTools().analyzePlan(
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("oracle");
        assertThat(field(summary, "nodeCount").asInt()).isGreaterThan(0);

        ObjectNode valid = object(queryAnalysisTools().validateQuery("SELECT * FROM customers", null, null));
        assertThat(field(valid, "valid").asBoolean()).isTrue();
        ObjectNode validNamed = object(queryAnalysisTools().validateQuery(
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

        ObjectNode invalid = object(queryAnalysisTools().validateQuery("DELETE FROM customers", null, null));
        assertThat(field(invalid, "valid").asBoolean()).isFalse();
        assertThat(field(invalid, "stage").asText()).isEqualTo("guard");
    }

    @Test
    void queryToolsRejectWrites() {
        assertRejected(() ->
                queryTools().executeQuery("DELETE FROM customers", null, null, null, null),
                "Only SELECT");
    }
}
