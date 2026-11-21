package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParamType;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParamTypeEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationQueryToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void executeQuerySupportsJsonLimitAndNamedParams() {
        ObjectNode result = object(queryTools().executeQuery(connection(),
                "SELECT name, email FROM customers ORDER BY id",
                null, null, null, 1, 5));

        assertThat(field(result, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(result, "truncated").asBoolean()).isTrue();
        assertThat(field(row(result, 0), "name").asText()).isEqualTo("Alice");

        ObjectNode count = object(queryTools().executeQuery(connection(),
                "SELECT COUNT(*) AS c FROM orders WHERE customer_id = :customerId",
                null, Map.of("customerId", 1), null, null, 5));
        assertThat(field(row(count, 0), "c").asInt()).isEqualTo(2);
    }

    @Test
    void explainAnalyzeAndValidateReturnToolLevelResponses() {
        String plan = queryAnalysisTools().explainQuery(connection(),
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, null, false);
        assertThat(plan).containsIgnoringCase("customers");

        ObjectNode summary = object(queryAnalysisTools().analyzePlan(connection(),
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("postgresql");
        assertThat(field(summary, "nodeCount").asInt()).isGreaterThan(0);

        ObjectNode valid = object(queryAnalysisTools().validateQuery(connection(), "SELECT * FROM customers", null, null, null));
        assertThat(field(valid, "valid").asBoolean()).isTrue();
        assertThat(field(field(valid, "inspection"), "parseable").asBoolean()).isTrue();
        ObjectNode validNamed = object(queryAnalysisTools().validateQuery(connection(),
                "SELECT COUNT(*) FROM events WHERE status = :status",
                null, Map.of("status", "PAID"), null));
        assertThat(field(validNamed, "valid").asBoolean()).isTrue();

        assertInvalidArgument(() ->
                queryTools().executeQuery(connection(),
                        "SELECT * FROM customers WHERE id = ?",
                        null, null, null, 5, 5),
                "contains '?' placeholders");
        assertInvalidArgument(() ->
                queryTools().executeQuery(connection(),
                        "SELECT * FROM customers WHERE id = :id",
                        java.util.List.of(1), null, null, 5, 5),
                "'namedParams'");

        ObjectNode invalid = object(queryAnalysisTools().validateQuery(connection(), "DELETE FROM customers", null, null, null));
        assertThat(field(invalid, "valid").asBoolean()).isFalse();
        assertThat(field(invalid, "stage").asText()).isEqualTo("guard");
        assertThat(field(invalid, "inspection")).isNotNull();
    }

    @Test
    void queryToolsRejectWrites() {
        assertRejected(() ->
                queryTools().executeQuery(connection(), "DELETE FROM customers", null, null, null, null, null),
                "Only SELECT");
    }

    @Test
    void inspectQueryAndQueryLintReturnAuthoringSignals() {
        ObjectNode inspection = object(queryAnalysisTools().inspectQuery(connection(), """
                SELECT c.name, o.total
                FROM customers c
                JOIN orders o ON o.customer_id = c.id
                WHERE c.email LIKE '%@example.com'
                ORDER BY o.total
                """));
        assertThat(field(inspection, "parseable").asBoolean()).isTrue();
        assertThat(field(inspection, "tables").size()).isEqualTo(2);
        assertThat(field(inspection, "predicates").size()).isEqualTo(1);

        ObjectNode lint = object(queryAnalysisTools().queryLint(connection(), """
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

    @Test
    void executeQuerySupportsTypedParamTypesWithExplicitOids() {
        ObjectNode typeofResult = object(queryTools().executeQuery(connection(),
                "SELECT pg_typeof(:d)::text AS d_type, "
                        + "pg_typeof(:ts)::text AS ts_type, "
                        + "pg_typeof(:tz)::text AS tz_type, "
                        + "pg_typeof(:u)::text AS u_type, "
                        + "pg_typeof(:n)::text AS n_type, "
                        + "pg_typeof(:dec)::text AS dec_type",
                null,
                Map.of(
                        "d", "2024-02-29",
                        "ts", "2024-02-29T15:30:45.123456",
                        "tz", "2024-02-29T15:30:45+03:00",
                        "u", "123e4567-e89b-12d3-a456-426614174000",
                        "n", "9007199254740993",
                        "dec", "123456789012345.67"),
                List.of(
                        new SqlParamTypeEntry("d", SqlParamType.date),
                        new SqlParamTypeEntry("ts", SqlParamType.timestamp),
                        new SqlParamTypeEntry("tz", SqlParamType.timestamptz),
                        new SqlParamTypeEntry("u", SqlParamType.uuid),
                        new SqlParamTypeEntry("n", SqlParamType.integer),
                        new SqlParamTypeEntry("dec", SqlParamType.decimal)),
                null,
                5));
        assertThat(field(row(typeofResult, 0), "d_type").asText()).isEqualTo("date");
        assertThat(field(row(typeofResult, 0), "ts_type").asText()).isEqualTo("timestamp without time zone");
        assertThat(field(row(typeofResult, 0), "tz_type").asText()).isEqualTo("timestamp with time zone");
        assertThat(field(row(typeofResult, 0), "u_type").asText()).isEqualTo("uuid");
        assertThat(field(row(typeofResult, 0), "n_type").asText()).isEqualTo("bigint");
        assertThat(field(row(typeofResult, 0), "dec_type").asText()).isEqualTo("numeric");

        ObjectNode bareSelect = object(queryTools().executeQuery(connection(),
                "SELECT ? AS d, ? AS ts",
                List.of("2024-02-29", "2024-02-29T15:30:45"),
                null,
                List.of(
                        new SqlParamTypeEntry("1", SqlParamType.date),
                        new SqlParamTypeEntry("2", SqlParamType.timestamp)),
                null,
                5));
        assertThat(textValues(array(field(bareSelect, "columnTypes"))))
                .containsExactly("date", "timestamp");

        ObjectNode typedNull = object(queryTools().executeQuery(connection(),
                "SELECT pg_typeof(?)::text AS t, ? IS NULL AS is_null",
                Collections.unmodifiableList(java.util.Arrays.asList(null, null)),
                null,
                List.of(
                        new SqlParamTypeEntry("1", SqlParamType.date),
                        new SqlParamTypeEntry("2", SqlParamType.date)),
                null,
                5));
        assertThat(field(row(typedNull, 0), "t").asText()).isEqualTo("date");
        assertThat(field(row(typedNull, 0), "is_null").asBoolean()).isTrue();
    }
}
