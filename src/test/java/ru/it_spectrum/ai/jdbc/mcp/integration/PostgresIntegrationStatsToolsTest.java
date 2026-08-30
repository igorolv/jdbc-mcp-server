package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationStatsToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void tableStatsAndIndexStatsExposeSeededObjects() {
        ObjectNode table = object(statsTools().tableStats("public", "customers", null));
        assertThat(field(table, "found").asBoolean()).isTrue();
        assertThat(field(table, "table").asText()).isEqualTo("customers");
        assertThat(field(table, "liveTuples").asLong()).isGreaterThanOrEqualTo(2L);

        ObjectNode indexStats = object(statsTools().indexStats("public", "customers", null));
        ArrayNode indexes = (ArrayNode) field(indexStats, "indexes");
        assertThat(findByField(indexes, "indexName", "customers_pkey")).isNotNull();
        assertThat(findByField(indexes, "indexName", "idx_customers_name")).isNotNull();
    }

    @Test
    void fkCoverageAndRedundantIndexesAreReported() {
        ObjectNode fkCoverage = object(statsTools().fkIndexCoverage("public", "orders", null));
        assertThat(field(fkCoverage, "uncoveredCount").asInt()).isGreaterThanOrEqualTo(1);
        ObjectNode uncovered = (ObjectNode) findByField((ArrayNode) field(fkCoverage, "uncovered"), "tableName", "orders");
        assertThat(uncovered).isNotNull();
        assertThat(textValues((ArrayNode) field(uncovered, "fkColumns"))).contains("customer_id");

        ObjectNode redundant = object(statsTools().redundantIndexes("public", "line_items", null));
        ObjectNode finding = (ObjectNode) findByField((ArrayNode) field(redundant, "findings"),
                "shadowedIndex", "idx_li_order");
        assertThat(finding).isNotNull();
        assertThat(field(finding, "coveredByIndex").asText()).isEqualTo("idx_li_order_sku");
    }

    @Test
    void unusedIndexesReturnsCandidates() {
        ObjectNode unused = object(statsTools().unusedIndexes("public", null, null));
        assertThat(field(unused, "supported").asBoolean()).isTrue();
        assertThat(findByField((ArrayNode) field(unused, "indexes"), "indexName", "idx_customer_notes_note"))
                .isNotNull();
    }
}
