package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OracleIntegrationDistributionToolsTest extends AbstractOracleToolsIntegrationTest {

    @Test
    void columnStatsReturnsBasicExtremes() {
        ObjectNode result = object(distributionTools().columnStats(connection(), schema(), "ORDERS", "TOTAL"));
        assertThat(field(result, "totalRows").asInt()).isEqualTo(3);
        assertThat(field(result, "nonNullRows").asInt()).isEqualTo(3);
        assertThat(field(result, "distinctValues").asInt()).isEqualTo(3);
    }

    @Test
    void distributionAndHistogramExposeSkew() {
        ObjectNode distribution = object(distributionTools().columnDistribution(connection(), schema(), "EVENTS", "STATUS", 5));
        assertThat(field(distribution, "totalRows").asInt()).isEqualTo(100);
        ObjectNode ok = (ObjectNode) findByField((ArrayNode) field(distribution, "values"), "value", "OK");
        assertThat(ok).isNotNull();
        assertThat(field(ok, "frequency").asInt()).isEqualTo(90);

        ObjectNode histogram = object(distributionTools().columnHistogram(connection(), schema(), "EVENTS", "AMOUNT"));
        assertThat(field(histogram, "percentileFunction").asText()).isEqualTo("percentile_cont");
        assertThat(field(histogram, "p50").asDouble())
                .isBetween(field(histogram, "min").asDouble(), field(histogram, "max").asDouble());
    }

    @Test
    void nullRatioSelectivityAndJoinCardinalityReturnPlannerSignals() {
        ObjectNode nullRatio = object(distributionTools().nullRatio(connection(), schema(), "EVENTS"));
        ObjectNode category = (ObjectNode) findByField((ArrayNode) field(nullRatio, "columns"), "column", "CATEGORY");
        assertThat(category).isNotNull();
        assertThat(field(category, "nullRows").asInt()).isEqualTo(10);

        ObjectNode selectivity = object(distributionTools().estimateSelectivity(connection(),
                schema(), "EVENTS", "status = 'FAIL'"));
        assertThat(field(selectivity, "estimatedRows").asLong())
                .isLessThanOrEqualTo(field(selectivity, "baselineRows").asLong());

        ObjectNode join = object(distributionTools().joinCardinality(connection(),
                schema(), "CUSTOMERS", "ID",
                schema(), "ORDERS", "CUSTOMER_ID", "INNER"));
        assertThat(field(join, "joinType").asText()).isEqualTo("INNER");
        assertThat(field(join, "estimatedRows").asLong()).isGreaterThan(0L);
    }

    @Test
    void selectivityRejectsMultipleStatementsAtToolBoundary() {
        assertInvalidArgument(() ->
                distributionTools().estimateSelectivity(connection(), schema(), "EVENTS", "status = 'OK'; DROP TABLE events"),
                "single boolean expression");
    }
}
