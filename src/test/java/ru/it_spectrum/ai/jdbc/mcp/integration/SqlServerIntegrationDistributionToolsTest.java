package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Tag("integration")
class SqlServerIntegrationDistributionToolsTest extends AbstractSqlServerToolsIntegrationTest {

    @Test
    void columnStatsReturnsBasicExtremes() {
        ObjectNode result = object(distributionTools().columnStats("dbo", "orders", "total"));
        assertThat(field(row(result, 0), "total_rows").asInt()).isEqualTo(3);
        assertThat(field(row(result, 0), "non_null_rows").asInt()).isEqualTo(3);
        assertThat(field(row(result, 0), "distinct_values").asInt()).isEqualTo(3);
    }

    @Test
    void distributionAndHistogramExposeSkew() {
        ObjectNode distribution = object(distributionTools().columnDistribution("dbo", "events", "status", 5));
        assertThat(field(distribution, "total_rows").asInt()).isEqualTo(100);
        ObjectNode ok = (ObjectNode) findByField((ArrayNode) field(distribution, "values"), "value", "OK");
        assertThat(ok).isNotNull();
        assertThat(field(ok, "frequency").asInt()).isEqualTo(90);
        assertThat(field(ok, "ratio").asDouble()).isCloseTo(0.9, within(1e-6));

        ObjectNode histogram = object(distributionTools().columnHistogram("dbo", "events", "amount"));
        assertThat(field(histogram, "percentile_function").asText()).isEqualTo("percentile_cont");
        assertThat(field(histogram, "p50").asDouble())
                .isBetween(field(histogram, "min").asDouble(), field(histogram, "max").asDouble());
    }

    @Test
    void nullRatioSelectivityAndJoinCardinalityReturnPlannerSignals() {
        ObjectNode nullRatio = object(distributionTools().nullRatio("dbo", "events"));
        ObjectNode category = (ObjectNode) findByField((ArrayNode) field(nullRatio, "columns"), "column", "category");
        assertThat(category).isNotNull();
        assertThat(field(category, "null_rows").asInt()).isEqualTo(10);

        ObjectNode selectivity = object(distributionTools().estimateSelectivity(
                "dbo", "events", "status = 'FAIL'"));
        assertThat(field(selectivity, "estimated_rows").asLong())
                .isLessThan(field(selectivity, "baseline_rows").asLong());
        assertThat(field(selectivity, "selectivity").asDouble()).isBetween(0.0, 1.0);

        ObjectNode join = object(distributionTools().joinCardinality(
                "dbo", "customers", "id",
                "dbo", "orders", "customer_id", "INNER"));
        assertThat(field(join, "join_type").asText()).isEqualTo("INNER");
        assertThat(field(join, "estimated_rows").asLong()).isGreaterThan(0L);
    }
}
