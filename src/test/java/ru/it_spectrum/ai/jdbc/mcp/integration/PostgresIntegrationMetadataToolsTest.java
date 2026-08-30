package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationMetadataToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void listsSchemasAndTables() {
        assertThat(textValues(array(metadataTools().listSchemas(connection(), false).schemas()))).contains("public");

        ArrayNode tables = array(metadataTools().listTables(connection(), "public", "%", null).tables());
        assertThat(findByField(tables, "name", "customers")).isNotNull();
        assertThat(findByField(tables, "name", "v_customer_totals")).isNotNull();
    }

    @Test
    void describesTableAndViewDefinition() {
        ObjectNode table = object(metadataTools().describeTable(connection(), "public", "orders"));
        assertThat(field(table, "name").asText()).isEqualTo("orders");
        assertThat(findByField((ArrayNode) field(table, "columns"), "name", "customer_id")).isNotNull();
        assertThat(field(field(table, "primaryKey"), "name").asText()).isEqualTo("orders_pkey");
        assertThat(findByField((ArrayNode) field(table, "checkConstraints"),
                "name", "orders_total_nonnegative")).isNotNull();

        String viewDefinition = metadataTools().getViewDefinition(connection(), "public", "v_customer_totals");
        assertThat(viewDefinition).containsIgnoringCase("FROM customers");

        ObjectNode events = object(metadataTools().describeTable(connection(), "public", "events"));
        ObjectNode statusCheckValues = (ObjectNode) findByField(
                (ArrayNode) field(events, "checkConstraints"), "name", "events_status_check");
        assertThat(textValues((ArrayNode) field(statusCheckValues, "allowedValues")))
                .containsExactly("OK", "FAIL");
    }

    @Test
    void exposesConstraintsAndTriggersViaDescribeTable() {
        ObjectNode orders = object(metadataTools().describeTable(connection(), "public", "orders"));
        ArrayNode checkConstraints = (ArrayNode) field(orders, "checkConstraints");
        ObjectNode check = (ObjectNode) findByField(checkConstraints, "name", "orders_total_nonnegative");
        assertThat(check).isNotNull();
        assertThat(field(check, "definition").asText()).contains("total >= 0");

        ObjectNode events = object(metadataTools().describeTable(connection(), "public", "events"));
        ArrayNode eventChecks = (ArrayNode) field(events, "checkConstraints");
        ObjectNode statusCheck = (ObjectNode) findByField(eventChecks, "name", "events_status_check");
        assertThat(statusCheck).isNotNull();
        assertThat(textValues((ArrayNode) field(statusCheck, "columns")))
                .anySatisfy(c -> assertThat(c).isEqualToIgnoringCase("status"));
        assertThat(textValues((ArrayNode) field(statusCheck, "allowedValues")))
                .containsExactly("OK", "FAIL");

        ObjectNode customerNotes = object(metadataTools().describeTable(connection(), "public", "customer_notes"));
        ArrayNode triggers = (ArrayNode) field(customerNotes, "triggers");
        ObjectNode trigger = (ObjectNode) findByField(triggers, "name", "customer_notes_touch_trg");
        assertThat(trigger).isNotNull();
        assertThat(field(trigger, "timing").asText()).isEqualTo("BEFORE");
        assertThat(textValues((ArrayNode) field(trigger, "events"))).contains("INSERT", "UPDATE");

        String definition = metadataTools().getTriggerDefinition(connection(),
                "public", "customer_notes", "customer_notes_touch_trg");
        assertThat(definition).contains("customer_notes_touch_trg");
    }

    @Test
    void listsRoutinesSequencesAndSearchResults() {
        ArrayNode routines = array(metadataTools().listRoutines(connection(), "public", "customer_count%").routines());
        assertThat(findByField(routines, "name", "customer_count_fn")).isNotNull();

        String source = metadataTools().getRoutineDefinition(connection(), "public", "customer_count_fn");
        assertThat(source).contains("COUNT(*)").contains("customers");

        ArrayNode sequences = array(metadataTools().listSequences(connection(), "public").sequences());
        assertThat(findByField(sequences, "name", "audit_seq")).isNotNull();

        ArrayNode search = array(metadataTools().searchObjects(connection(), "customer").objects());
        assertThat(findByField(search, "name", "customers")).isNotNull();
        assertThat(findByField(search, "name", "v_customer_totals")).isNotNull();
    }
}
