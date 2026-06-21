package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OracleIntegrationStatsToolsTest extends AbstractOracleToolsIntegrationTest {

    @Test
    void tableStatsAndIndexStatsExposeSeededObjects() {
        ObjectNode table = object(statsTools().tableStats(schema(), "CUSTOMERS"));
        assertThat(field(table, "found").asBoolean()).isTrue();
        assertThat(field(table, "table").asText()).isEqualTo("CUSTOMERS");
        assertThat(field(table, "estimatedRows").asLong()).isGreaterThanOrEqualTo(2L);

        ObjectNode indexStats = object(statsTools().indexStats(schema(), "CUSTOMERS"));
        ArrayNode indexes = (ArrayNode) field(indexStats, "indexes");
        assertThat(findByField(indexes, "indexName", "IDX_CUSTOMERS_NAME")).isNotNull();
    }

    @Test
    void fkCoverageAndRedundantIndexesAreReported() {
        ObjectNode fkCoverage = object(statsTools().fkIndexCoverage(schema(), "ORDERS"));
        assertThat(field(fkCoverage, "uncoveredCount").asInt()).isGreaterThanOrEqualTo(1);
        ObjectNode uncovered = (ObjectNode) findByField((ArrayNode) field(fkCoverage, "uncovered"), "tableName", "ORDERS");
        assertThat(uncovered).isNotNull();
        assertThat(textValues((ArrayNode) field(uncovered, "fkColumns"))).contains("CUSTOMER_ID");

        ObjectNode redundant = object(statsTools().redundantIndexes(schema(), "LINE_ITEMS"));
        ObjectNode finding = (ObjectNode) findByField((ArrayNode) field(redundant, "findings"),
                "shadowedIndex", "IDX_LI_ORDER");
        assertThat(finding).isNotNull();
        assertThat(field(finding, "coveredByIndex").asText()).isEqualTo("IDX_LI_ORDER_SKU");
    }

    @Test
    void unusedIndexesReturnsOracleDiagnostic() {
        ObjectNode unused = object(statsTools().unusedIndexes(schema(), null));
        assertThat(field(unused, "supported").asBoolean()).isFalse();
        assertThat(field(unused, "note").asText()).contains("DBA_INDEX_USAGE");
    }
}
