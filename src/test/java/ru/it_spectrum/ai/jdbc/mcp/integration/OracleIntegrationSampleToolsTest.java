package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OracleIntegrationSampleToolsTest extends AbstractOracleToolsIntegrationTest {

    @Test
    void sampleRowsReturnsLimitedJsonResult() {
        ObjectNode result = object(sampleTools().sampleRows(schema(), "CUSTOMERS", 1));
        assertThat(field(result, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(result, "truncated").asBoolean()).isFalse();
        assertThat(field(row(result, 0), "NAME").asText()).isEqualTo("Alice");
    }

    @Test
    void columnStatsReturnsBasicExtremes() {
        ObjectNode result = object(sampleTools().columnStats(schema(), "ORDERS", "TOTAL"));
        assertThat(field(row(result, 0), "TOTAL_ROWS").asInt()).isEqualTo(3);
        assertThat(field(row(result, 0), "NON_NULL_ROWS").asInt()).isEqualTo(3);
        assertThat(field(row(result, 0), "DISTINCT_VALUES").asInt()).isEqualTo(3);
    }
}
