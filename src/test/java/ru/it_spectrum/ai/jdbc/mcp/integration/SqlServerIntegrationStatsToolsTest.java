package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class SqlServerIntegrationStatsToolsTest extends AbstractSqlServerToolsIntegrationTest {

    @Test
    void tableStatsAndIndexStatsExposeSeededObjects() {
        ObjectNode table = object(statsTools().tableStats("dbo", "customers"));
        assertThat(field(table, "found").asBoolean()).isTrue();
        assertThat(field(table, "table_name").asText()).isEqualTo("customers");
        assertThat(field(table, "estimated_rows").asLong()).isGreaterThanOrEqualTo(2L);

        ArrayNode indexes = rows(statsTools().indexStats("dbo", "customers"));
        assertThat(findByField(indexes, "index_name", "idx_customers_name")).isNotNull();
    }

    @Test
    void fkCoverageAndRedundantIndexesAreReported() {
        ObjectNode fkCoverage = object(statsTools().fkIndexCoverage("dbo", "orders"));
        assertThat(field(fkCoverage, "uncovered_count").asInt()).isGreaterThanOrEqualTo(1);
        ObjectNode uncovered = (ObjectNode) findByField((ArrayNode) field(fkCoverage, "uncovered"), "table", "orders");
        assertThat(uncovered).isNotNull();
        assertThat(textValues((ArrayNode) field(uncovered, "fk_columns"))).contains("customer_id");

        ObjectNode redundant = object(statsTools().redundantIndexes("dbo", "line_items"));
        ObjectNode finding = (ObjectNode) findByField((ArrayNode) field(redundant, "findings"),
                "shadowed_index", "idx_li_order");
        assertThat(finding).isNotNull();
        assertThat(field(finding, "covered_by_index").asText()).isEqualTo("idx_li_order_sku");
    }

    @Test
    void unusedIndexesReportsUnsupportedForLowPrivilegeSqlServerPath() {
        ObjectNode unused = object(statsTools().unusedIndexes("dbo", null));
        assertThat(field(unused, "supported").asBoolean()).isFalse();
        assertThat(field(unused, "note").asText()).contains("SQL Server");
    }
}
