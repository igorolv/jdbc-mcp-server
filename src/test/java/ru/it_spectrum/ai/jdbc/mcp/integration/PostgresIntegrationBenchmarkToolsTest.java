package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationBenchmarkToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void benchmarkQueryReturnsTimingSummary() {
        ObjectNode result = object(benchmarkTools().benchmarkQuery(
                "SELECT * FROM events ORDER BY id", null, null, 50, 5, 1, 3));
        assertThat(field(result, "runs").asInt()).isEqualTo(4);
        assertThat(field(field(result, "result_size"), "row_count").asInt()).isEqualTo(50);
        assertThat(field(field(result, "result_size"), "truncated").asBoolean()).isTrue();
    }

    @Test
    void benchmarkQuerySupportsNamedParams() {
        ObjectNode result = object(benchmarkTools().benchmarkQuery(
                "SELECT * FROM events WHERE status = :status ORDER BY id",
                null, Map.of("status", "OK"), 50, 5, 1, 2));
        assertThat(field(result, "runs").asInt()).isEqualTo(3);
        assertThat(field(field(result, "result_size"), "row_count").asInt()).isEqualTo(50);
    }

    @Test
    void timedQueryReportsRowsAndGuardFailures() {
        ObjectNode result = object(benchmarkTools().timedQuery(
                "SELECT * FROM events WHERE status = ?",
                java.util.List.of("OK"), null, 200, 5));
        assertThat(field(result, "row_count").asInt()).isEqualTo(90);
        assertThat(field(field(result, "pg_stat_statements"), "available").asBoolean()).isFalse();

        assertRejected(
                benchmarkTools().benchmarkQuery("DELETE FROM events", null, null, 10, 5, 1, 1),
                "Only SELECT");
        assertInvalidArgument(
                benchmarkTools().benchmarkQuery("SELECT 1", null, null, 0, 5, 1, 1),
                "limit");
    }
}
