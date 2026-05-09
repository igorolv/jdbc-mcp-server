package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Tag("integration")
class PostgresIntegrationDistributionToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void columnStatsReturnsBasicExtremes() {
        ObjectNode result = object(distributionTools().columnStats("public", "orders", "total"));
        assertThat(field(row(result, 0), "total_rows").asInt()).isEqualTo(3);
        assertThat(field(row(result, 0), "non_null_rows").asInt()).isEqualTo(3);
        assertThat(field(row(result, 0), "distinct_values").asInt()).isEqualTo(3);
    }

    @Test
    void distributionAndHistogramExposeSkew() {
        ObjectNode distribution = object(distributionTools().columnDistribution("public", "events", "status", 5));
        assertThat(field(distribution, "totalRows").asInt()).isEqualTo(100);
        ObjectNode ok = (ObjectNode) findByField((ArrayNode) field(distribution, "values"), "value", "OK");
        assertThat(ok).isNotNull();
        assertThat(field(ok, "frequency").asInt()).isEqualTo(90);
        assertThat(field(ok, "ratio").asDouble()).isCloseTo(0.9, within(1e-6));

        ObjectNode histogram = object(distributionTools().columnHistogram("public", "events", "amount"));
        assertThat(field(histogram, "percentileFunction").asText()).isEqualTo("percentile_cont");
        assertThat(field(histogram, "p50").asDouble())
                .isBetween(field(histogram, "min").asDouble(), field(histogram, "max").asDouble());
    }

    @Test
    void nullRatioSelectivityAndJoinCardinalityReturnPlannerSignals() {
        ObjectNode nullRatio = object(distributionTools().nullRatio("public", "events"));
        ObjectNode category = (ObjectNode) findByField((ArrayNode) field(nullRatio, "columns"), "column", "category");
        assertThat(category).isNotNull();
        assertThat(field(category, "nullRows").asInt()).isEqualTo(10);

        ObjectNode selectivity = object(distributionTools().estimateSelectivity(
                "public", "events", "status = 'FAIL'"));
        assertThat(field(selectivity, "estimatedRows").asLong())
                .isLessThan(field(selectivity, "baselineRows").asLong());
        assertThat(field(selectivity, "selectivity").asDouble()).isBetween(0.0, 1.0);

        ObjectNode join = object(distributionTools().joinCardinality(
                "public", "customers", "id",
                "public", "orders", "customer_id", "INNER"));
        assertThat(field(join, "joinType").asText()).isEqualTo("INNER");
        assertThat(field(join, "estimatedRows").asLong()).isGreaterThan(0L);
    }

    @Test
    void selectivityRejectsMultipleStatementsAtToolBoundary() {
        assertInvalidArgument(
                distributionTools().estimateSelectivity("public", "events", "status = 'OK'; DROP TABLE events"),
                "single boolean expression");
    }
}
