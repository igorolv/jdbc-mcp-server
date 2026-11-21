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
class OracleIntegrationQueryToolsTest extends AbstractOracleToolsIntegrationTest {

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
        assertThat(field(row(count, 0), "C").asInt()).isEqualTo(2);
    }

    @Test
    void explainAnalyzeAndValidateReturnToolLevelResponses() {
        String plan = queryAnalysisTools().explainQuery(connection(),
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, null, false);
        assertThat(plan).containsIgnoringCase("CUSTOMERS");

        ObjectNode summary = object(queryAnalysisTools().analyzePlan(connection(),
                "SELECT * FROM customers WHERE name LIKE 'A%'", null, null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("oracle");
        assertThat(field(summary, "nodeCount").asInt()).isGreaterThan(0);

        ObjectNode valid = object(queryAnalysisTools().validateQuery(connection(), "SELECT * FROM customers", null, null, null));
        assertThat(field(valid, "valid").asBoolean()).isTrue();
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
    }

    @Test
    void queryToolsRejectWrites() {
        assertRejected(() ->
                queryTools().executeQuery(connection(), "DELETE FROM customers", null, null, null, null, null),
                "Only SELECT");
    }

    @Test
    void executeQuerySupportsTypedParamTypesOnOracle() {
        ObjectNode typed = object(queryTools().executeQuery(connection(),
                "SELECT CASE WHEN :d = DATE '2024-02-29' THEN 1 ELSE 0 END AS ok_date, "
                        + "DUMP(:d) AS d_dump, "
                        + "CASE WHEN :ts = TIMESTAMP '2024-02-29 15:30:45.123456' THEN 1 ELSE 0 END AS ok_ts, "
                        + "CASE WHEN :tz = TIMESTAMP '2024-02-29 12:30:45 UTC' THEN 1 ELSE 0 END AS ok_tz, "
                        + "TO_CHAR(:n, 'FM999999999999999999999') AS n_txt, "
                        + "TO_CHAR(:dec, 'FM999999999999999.999999', 'NLS_NUMERIC_CHARACTERS=''.,''') AS dec_txt "
                        + "FROM dual",
                null,
                Map.of(
                        "d", "2024-02-29",
                        "ts", "2024-02-29T15:30:45.123456",
                        "tz", "2024-02-29T15:30:45+03:00",
                        "n", "9007199254740993",
                        "dec", "123456789012345.678901"),
                List.of(
                        new SqlParamTypeEntry("d", SqlParamType.date),
                        new SqlParamTypeEntry("ts", SqlParamType.timestamp),
                        new SqlParamTypeEntry("tz", SqlParamType.timestamptz),
                        new SqlParamTypeEntry("n", SqlParamType.integer),
                        new SqlParamTypeEntry("dec", SqlParamType.decimal)),
                null,
                5));
        assertThat(field(row(typed, 0), "OK_DATE").asInt()).isEqualTo(1);
        assertThat(field(row(typed, 0), "D_DUMP").asText()).matches("^Typ=(12|13) .*");
        assertThat(field(row(typed, 0), "OK_TS").asInt()).isEqualTo(1);
        assertThat(field(row(typed, 0), "OK_TZ").asInt()).isEqualTo(1);
        assertThat(field(row(typed, 0), "N_TXT").asText()).isEqualTo("9007199254740993");
        assertThat(field(row(typed, 0), "DEC_TXT").asText()).isEqualTo("123456789012345.678901");

        ObjectNode typedNull = object(queryTools().executeQuery(connection(),
                "SELECT CASE WHEN ? IS NULL THEN 1 ELSE 0 END AS ok FROM dual",
                Collections.singletonList(null),
                null,
                List.of(new SqlParamTypeEntry("1", SqlParamType.date)),
                null,
                5));
        assertThat(field(row(typedNull, 0), "OK").asInt()).isEqualTo(1);
    }
}
