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
        assertThat(textValues(array(metadataTools().listSchemas(false).schemas()))).contains(schema());

        ArrayNode tables = array(metadataTools().listTables(schema(), "%", null).tables());
        assertThat(findByField(tables, "name", "CUSTOMERS")).isNotNull();
        assertThat(findByField(tables, "name", "V_CUSTOMER_TOTALS")).isNotNull();
    }

    @Test
    void describesTableAndViewDefinition() {
        ObjectNode table = object(metadataTools().describeTable(schema(), "ORDERS"));
        assertThat(field(table, "name").asText()).isEqualTo("ORDERS");
        assertThat(findByField((ArrayNode) field(table, "columns"), "name", "CUSTOMER_ID")).isNotNull();
        assertThat(field(field(table, "primaryKey"), "name").asText()).isNotBlank();
        assertThat(findByField((ArrayNode) field(table, "constraints"),
                "name", "ORDERS_TOTAL_NONNEGATIVE")).isNotNull();

        String viewDefinition = metadataTools().getViewDefinition(schema(), "V_CUSTOMER_TOTALS");
        assertThat(viewDefinition).containsIgnoringCase("FROM customers");

        ObjectNode events = object(metadataTools().describeTable(schema(), "EVENTS"));
        assertThat(textValues((ArrayNode) field(field(events, "allowedValues"), "status")))
                .containsExactly("OK", "FAIL");
    }

    @Test
    void exposesConstraintsAndTriggersViaDescribeTable() {
        ObjectNode orders = object(metadataTools().describeTable(schema(), "ORDERS"));
        ArrayNode constraints = (ArrayNode) field(orders, "constraints");
        ObjectNode check = (ObjectNode) findByField(constraints, "name", "ORDERS_TOTAL_NONNEGATIVE");
        assertThat(check).isNotNull();
        assertThat(field(check, "type").asText()).isEqualTo("CHECK");
        assertThat(field(check, "definition").asText()).containsIgnoringCase("total");

        ObjectNode events = object(metadataTools().describeTable(schema(), "EVENTS"));
        ArrayNode eventConstraints = (ArrayNode) field(events, "constraints");
        ObjectNode statusCheck = (ObjectNode) findByField(eventConstraints, "name", "EVENTS_STATUS_CHECK");
        assertThat(statusCheck).isNotNull();
        assertThat(field(statusCheck, "allowedValuesColumn").asText()).isEqualTo("status");
        assertThat(textValues((ArrayNode) field(statusCheck, "allowedValues")))
                .containsExactly("OK", "FAIL");

        ObjectNode customerNotes = object(metadataTools().describeTable(schema(), "CUSTOMER_NOTES"));
        ArrayNode triggers = (ArrayNode) field(customerNotes, "triggers");
        ObjectNode trigger = (ObjectNode) findByField(triggers, "name", "CUSTOMER_NOTES_TOUCH_TRG");
        assertThat(trigger).isNotNull();
        assertThat(field(trigger, "timing").asText()).isEqualTo("BEFORE");
        assertThat(textValues((ArrayNode) field(trigger, "events"))).contains("INSERT", "UPDATE");

        String definition = metadataTools().getTriggerDefinition(
                schema(), "CUSTOMER_NOTES", "CUSTOMER_NOTES_TOUCH_TRG");
        assertThat(definition).containsIgnoringCase("customer_notes_touch_trg");
    }

    @Test
    void listsRoutinesSequencesAndSearchResults() {
        ArrayNode routines = array(metadataTools().listRoutines(schema(), "CUSTOMER_COUNT%").routines());
        assertThat(findByField(routines, "name", "CUSTOMER_COUNT_FN")).isNotNull();

        String source = metadataTools().getRoutineDefinition(schema(), "CUSTOMER_COUNT_FN");
        assertThat(source).contains("COUNT(*)").contains("RETURN v_count");

        ArrayNode sequences = array(metadataTools().listSequences(schema()).sequences());
        assertThat(findByField(sequences, "name", "AUDIT_SEQ")).isNotNull();

        ArrayNode search = array(metadataTools().searchObjects("CUSTOMER").objects());
        assertThat(findByField(search, "name", "CUSTOMERS")).isNotNull();
        assertThat(findByField(search, "name", "V_CUSTOMER_TOTALS")).isNotNull();
    }
}
