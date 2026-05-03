package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationStatsToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void tableStatsAndIndexStatsExposeSeededObjects() {
        ObjectNode table = object(statsTools().tableStats("public", "customers"));
        assertThat(field(table, "found").asBoolean()).isTrue();
        assertThat(field(table, "table_name").asText()).isEqualTo("customers");
        assertThat(field(table, "live_tuples").asLong()).isGreaterThanOrEqualTo(2L);

        ArrayNode indexes = rows(statsTools().indexStats("public", "customers"));
        assertThat(findByField(indexes, "index_name", "customers_pkey")).isNotNull();
        assertThat(findByField(indexes, "index_name", "idx_customers_name")).isNotNull();
    }

    @Test
    void fkCoverageAndRedundantIndexesAreReported() {
        ObjectNode fkCoverage = object(statsTools().fkIndexCoverage("public", "orders"));
        assertThat(field(fkCoverage, "uncovered_count").asInt()).isGreaterThanOrEqualTo(1);
        ObjectNode uncovered = (ObjectNode) findByField((ArrayNode) field(fkCoverage, "uncovered"), "table", "orders");
        assertThat(uncovered).isNotNull();
        assertThat(textValues((ArrayNode) field(uncovered, "fk_columns"))).contains("customer_id");

        ObjectNode redundant = object(statsTools().redundantIndexes("public", "line_items"));
        ObjectNode finding = (ObjectNode) findByField((ArrayNode) field(redundant, "findings"),
                "shadowed_index", "idx_li_order");
        assertThat(finding).isNotNull();
        assertThat(field(finding, "covered_by_index").asText()).isEqualTo("idx_li_order_sku");
    }

    @Test
    void unusedIndexesReturnsCandidates() {
        ObjectNode unused = object(statsTools().unusedIndexes("public", null));
        assertThat(field(unused, "supported").asBoolean()).isTrue();
        assertThat(findByField((ArrayNode) field(unused, "indexes"), "index", "idx_customer_notes_note"))
                .isNotNull();
    }
}
