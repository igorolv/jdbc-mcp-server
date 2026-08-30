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
        assertThat(textValues(array(metadataTools().listSchemas(false, null).schemas()))).contains("public");

        ArrayNode tables = array(metadataTools().listTables("public", "%", null, null).tables());
        assertThat(findByField(tables, "name", "customers")).isNotNull();
        assertThat(findByField(tables, "name", "v_customer_totals")).isNotNull();
    }

    @Test
    void describesTableAndViewDefinition() {
        ObjectNode table = object(metadataTools().describeTable("public", "orders", null));
        assertThat(field(table, "name").asText()).isEqualTo("orders");
        assertThat(findByField((ArrayNode) field(table, "columns"), "name", "customer_id")).isNotNull();
        assertThat(field(field(table, "primaryKey"), "name").asText()).isEqualTo("orders_pkey");
        assertThat(findByField((ArrayNode) field(table, "checkConstraints"),
                "name", "orders_total_nonnegative")).isNotNull();

        String viewDefinition = metadataTools().getViewDefinition("public", "v_customer_totals", null);
        assertThat(viewDefinition).containsIgnoringCase("FROM customers");

        ObjectNode events = object(metadataTools().describeTable("public", "events", null));
        ObjectNode statusCheckValues = (ObjectNode) findByField(
                (ArrayNode) field(events, "checkConstraints"), "name", "events_status_check");
        assertThat(textValues((ArrayNode) field(statusCheckValues, "allowedValues")))
                .containsExactly("OK", "FAIL");
    }

    @Test
    void exposesConstraintsAndTriggersViaDescribeTable() {
        ObjectNode orders = object(metadataTools().describeTable("public", "orders", null));
        ArrayNode checkConstraints = (ArrayNode) field(orders, "checkConstraints");
        ObjectNode check = (ObjectNode) findByField(checkConstraints, "name", "orders_total_nonnegative");
        assertThat(check).isNotNull();
        assertThat(field(check, "definition").asText()).contains("total >= 0");

        ObjectNode events = object(metadataTools().describeTable("public", "events", null));
        ArrayNode eventChecks = (ArrayNode) field(events, "checkConstraints");
        ObjectNode statusCheck = (ObjectNode) findByField(eventChecks, "name", "events_status_check");
        assertThat(statusCheck).isNotNull();
        assertThat(textValues((ArrayNode) field(statusCheck, "columns")))
                .anySatisfy(c -> assertThat(c).isEqualToIgnoringCase("status"));
        assertThat(textValues((ArrayNode) field(statusCheck, "allowedValues")))
                .containsExactly("OK", "FAIL");

        ObjectNode customerNotes = object(metadataTools().describeTable("public", "customer_notes", null));
        ArrayNode triggers = (ArrayNode) field(customerNotes, "triggers");
        ObjectNode trigger = (ObjectNode) findByField(triggers, "name", "customer_notes_touch_trg");
        assertThat(trigger).isNotNull();
        assertThat(field(trigger, "timing").asText()).isEqualTo("BEFORE");
        assertThat(textValues((ArrayNode) field(trigger, "events"))).contains("INSERT", "UPDATE");

        String definition = metadataTools().getTriggerDefinition(
                "public", "customer_notes", "customer_notes_touch_trg", null);
        assertThat(definition).contains("customer_notes_touch_trg");
    }

    @Test
    void listsRoutinesSequencesAndSearchResults() {
        ArrayNode routines = array(metadataTools().listRoutines("public", "customer_count%", null).routines());
        assertThat(findByField(routines, "name", "customer_count_fn")).isNotNull();

        String source = metadataTools().getRoutineDefinition("public", "customer_count_fn", null);
        assertThat(source).contains("COUNT(*)").contains("customers");

        ArrayNode sequences = array(metadataTools().listSequences("public", null).sequences());
        assertThat(findByField(sequences, "name", "audit_seq")).isNotNull();

        ArrayNode search = array(metadataTools().searchObjects("customer", null).objects());
        assertThat(findByField(search, "name", "customers")).isNotNull();
        assertThat(findByField(search, "name", "v_customer_totals")).isNotNull();
    }
}
