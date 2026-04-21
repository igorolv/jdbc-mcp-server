package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OracleIntegrationDistributionToolsTest extends AbstractOracleToolsIntegrationTest {

    @Test
    void distributionAndHistogramExposeSkew() {
        ObjectNode distribution = object(distributionTools().columnDistribution(schema(), "EVENTS", "STATUS", 5));
        assertThat(field(distribution, "total_rows").asInt()).isEqualTo(100);
        ObjectNode ok = (ObjectNode) findByField((ArrayNode) field(distribution, "values"), "value", "OK");
        assertThat(ok).isNotNull();
        assertThat(field(ok, "frequency").asInt()).isEqualTo(90);

        ObjectNode histogram = object(distributionTools().columnHistogram(schema(), "EVENTS", "AMOUNT"));
        assertThat(field(histogram, "percentile_function").asText()).isEqualTo("percentile_cont");
        assertThat(field(histogram, "p50").asDouble())
                .isBetween(field(histogram, "min").asDouble(), field(histogram, "max").asDouble());
    }

    @Test
    void nullRatioSelectivityAndJoinCardinalityReturnPlannerSignals() {
        ObjectNode nullRatio = object(distributionTools().nullRatio(schema(), "EVENTS"));
        ObjectNode category = (ObjectNode) findByField((ArrayNode) field(nullRatio, "columns"), "column", "CATEGORY");
        assertThat(category).isNotNull();
        assertThat(field(category, "null_rows").asInt()).isEqualTo(10);

        ObjectNode selectivity = object(distributionTools().estimateSelectivity(
                schema(), "EVENTS", "status = 'FAIL'"));
        assertThat(field(selectivity, "estimated_rows").asLong())
                .isLessThanOrEqualTo(field(selectivity, "baseline_rows").asLong());

        ObjectNode join = object(distributionTools().joinCardinality(
                schema(), "CUSTOMERS", "ID",
                schema(), "ORDERS", "CUSTOMER_ID", "INNER"));
        assertThat(field(join, "join_type").asText()).isEqualTo("INNER");
        assertThat(field(join, "estimated_rows").asLong()).isGreaterThan(0L);
    }

    @Test
    void selectivityRejectsMultipleStatementsAtToolBoundary() {
        assertInvalidArgument(
                distributionTools().estimateSelectivity(schema(), "EVENTS", "status = 'OK'; DROP TABLE events"),
                "single boolean expression");
    }
}
