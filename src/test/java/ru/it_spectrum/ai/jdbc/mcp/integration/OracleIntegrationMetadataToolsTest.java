package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OracleIntegrationMetadataToolsTest extends AbstractOracleToolsIntegrationTest {

    @Test
    void listsSchemasAndTables() {
        assertThat(textValues(array(metadataTools().listSchemas(false)))).contains(schema());

        ArrayNode tables = rows(metadataTools().listTables(schema(), "%", null));
        assertThat(findByField(tables, "name", "CUSTOMERS")).isNotNull();
        assertThat(findByField(tables, "name", "V_CUSTOMER_TOTALS")).isNotNull();
    }

    @Test
    void describesTableAndViewDefinition() {
        ObjectNode table = object(metadataTools().describeTable(schema(), "ORDERS"));
        assertThat(field(table, "name").asText()).isEqualTo("ORDERS");
        assertThat(findByField((ArrayNode) field(table, "columns"), "name", "CUSTOMER_ID")).isNotNull();
        assertThat(field(field(table, "primaryKey"), "name").asText()).isNotBlank();

        String viewDefinition = metadataTools().getViewDefinition(schema(), "V_CUSTOMER_TOTALS");
        assertThat(viewDefinition).containsIgnoringCase("FROM customers");
    }

    @Test
    void listsRoutinesSequencesAndSearchResults() {
        ArrayNode routines = rows(metadataTools().listRoutines(schema(), "CUSTOMER_COUNT%"));
        assertThat(findByField(routines, "name", "CUSTOMER_COUNT_FN")).isNotNull();

        String source = metadataTools().getRoutineDefinition(schema(), "CUSTOMER_COUNT_FN");
        assertThat(source).contains("COUNT(*)").contains("RETURN v_count");

        ArrayNode sequences = rows(metadataTools().listSequences(schema()));
        assertThat(findByField(sequences, "name", "AUDIT_SEQ")).isNotNull();

        ArrayNode search = rows(metadataTools().searchObjects("CUSTOMER"));
        assertThat(findByField(search, "name", "CUSTOMERS")).isNotNull();
        assertThat(findByField(search, "name", "V_CUSTOMER_TOTALS")).isNotNull();
    }
}
