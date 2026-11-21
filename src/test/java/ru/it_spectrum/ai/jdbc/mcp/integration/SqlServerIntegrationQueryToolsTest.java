package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParamType;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParamTypeEntry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class SqlServerIntegrationQueryToolsTest extends AbstractSqlServerToolsIntegrationTest {

    @Test
    void executeQuerySupportsJsonLimitAndNamedParams() {
        ObjectNode result = object(queryTools().executeQuery(connection(),
                "SELECT name, email FROM dbo.customers ORDER BY id",
                null, null, null, 1, 5));

        assertThat(field(result, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(result, "truncated").asBoolean()).isTrue();
        assertThat(field(row(result, 0), "name").asText()).isEqualTo("Alice");

        ObjectNode count = object(queryTools().executeQuery(connection(),
                "SELECT COUNT(*) AS c FROM dbo.orders WHERE customer_id = :customerId",
                null, Map.of("customerId", 1), null, null, 5));
        assertThat(field(row(count, 0), "c").asInt()).isEqualTo(2);
    }

    @Test
    void explainAnalyzeAndValidateReturnToolLevelResponses() {
        String plan = queryAnalysisTools().explainQuery(connection(),
                "SELECT * FROM dbo.customers WHERE name LIKE 'A%'", null, null, null, false);
        assertThat(plan).containsIgnoringCase("customers");

        ObjectNode summary = object(queryAnalysisTools().analyzePlan(connection(),
                "SELECT * FROM dbo.customers WHERE name LIKE 'A%'", null, null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("mssql");
        assertThat(field(summary, "nodeCount").asInt()).isGreaterThan(0);

        ObjectNode valid = object(queryAnalysisTools().validateQuery(connection(), "SELECT * FROM dbo.customers", null, null, null));
        assertThat(field(valid, "valid").asBoolean()).isTrue();
        assertThat(field(field(valid, "inspection"), "parseable").asBoolean()).isTrue();
    }

    @Test
    void queryToolsRejectWrites() {
        assertRejected(() ->
                queryTools().executeQuery(connection(), "DELETE FROM dbo.customers", null, null, null, null, null),
                "Only SELECT");
    }

    @Test
    void inspectQueryAndQueryLintReturnAuthoringSignals() {
        ObjectNode inspection = object(queryAnalysisTools().inspectQuery(connection(), """
                SELECT c.name, o.total
                FROM dbo.customers c
                JOIN dbo.orders o ON o.customer_id = c.id
                WHERE c.email LIKE '%@example.com'
                ORDER BY o.total
                """));
        assertThat(field(inspection, "parseable").asBoolean()).isTrue();
        assertThat(field(inspection, "tables").size()).isEqualTo(2);

        ObjectNode lint = object(queryAnalysisTools().queryLint(connection(), """
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

    @Test
    void executeQueryAndExplainSupportTypedParamTypes() {
        String sql = "SELECT CASE WHEN :d = CAST('2024-02-29' AS DATE) THEN 1 ELSE 0 END AS ok_date, "
                + "CASE WHEN :ts = CAST('2024-02-29T15:30:45.123456' AS DATETIME2) THEN 1 ELSE 0 END AS ok_ts, "
                + "CASE WHEN :tz = CAST('2024-02-29T15:30:45+03:00' AS DATETIMEOFFSET) THEN 1 ELSE 0 END AS ok_tz, "
                + "CASE WHEN :u = CAST('123e4567-e89b-12d3-a456-426614174000' AS UNIQUEIDENTIFIER) THEN 1 ELSE 0 END AS ok_uuid, "
                + "CAST(:n AS VARCHAR(30)) AS n_txt";
        Map<String, Object> namedParams = Map.of(
                "d", "2024-02-29",
                "ts", "2024-02-29T15:30:45.123456",
                "tz", "2024-02-29T15:30:45+03:00",
                "u", "123e4567-e89b-12d3-a456-426614174000",
                "n", "9007199254740993");
        List<SqlParamTypeEntry> paramTypes = List.of(
                new SqlParamTypeEntry("d", SqlParamType.date),
                new SqlParamTypeEntry("ts", SqlParamType.timestamp),
                new SqlParamTypeEntry("tz", SqlParamType.timestamptz),
                new SqlParamTypeEntry("u", SqlParamType.uuid),
                new SqlParamTypeEntry("n", SqlParamType.integer));

        ObjectNode typed = object(queryTools().executeQuery(connection(), sql, null, namedParams, paramTypes, null, 5));
        assertThat(field(row(typed, 0), "ok_date").asInt()).isEqualTo(1);
        assertThat(field(row(typed, 0), "ok_ts").asInt()).isEqualTo(1);
        assertThat(field(row(typed, 0), "ok_tz").asInt()).isEqualTo(1);
        assertThat(field(row(typed, 0), "ok_uuid").asInt()).isEqualTo(1);
        assertThat(field(row(typed, 0), "n_txt").asText()).isEqualTo("9007199254740993");

        String plan = queryAnalysisTools().explainQuery(connection(), sql, null, namedParams, paramTypes, false);
        assertThat(plan).isNotBlank();
    }
}
