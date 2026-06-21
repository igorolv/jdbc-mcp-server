package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class SqlServerIntegrationMetadataToolsTest extends AbstractSqlServerToolsIntegrationTest {

    @Test
    void listsSchemasAndTables() {
        assertThat(textValues(array(metadataTools().listSchemas(false).schemas()))).contains("dbo");

        ArrayNode tables = array(metadataTools().listTables("dbo", "%", null).tables());
        assertThat(findByField(tables, "name", "customers")).isNotNull();
        assertThat(findByField(tables, "name", "v_customer_totals")).isNotNull();
    }

    @Test
    void describesTableAndViewDefinition() {
        ObjectNode table = object(metadataTools().describeTable("dbo", "orders"));
        assertThat(field(table, "name").asText()).isEqualTo("orders");
        assertThat(findByField((ArrayNode) field(table, "columns"), "name", "customer_id")).isNotNull();
        assertThat(field(field(table, "primaryKey"), "name").asText()).isNotBlank();
        assertThat(findByField((ArrayNode) field(table, "checkConstraints"),
                "name", "orders_total_nonnegative")).isNotNull();

        String viewDefinition = metadataTools().getViewDefinition("dbo", "v_customer_totals");
        assertThat(viewDefinition).containsIgnoringCase("FROM dbo.customers");
    }

    @Test
    void exposesConstraintsAndTriggersViaDescribeTable() {
        ObjectNode orders = object(metadataTools().describeTable("dbo", "orders"));
        ArrayNode checkConstraints = (ArrayNode) field(orders, "checkConstraints");
        ObjectNode check = (ObjectNode) findByField(checkConstraints, "name", "orders_total_nonnegative");
        assertThat(check).isNotNull();
        assertThat(field(check, "definition").asText()).contains("total");

        ObjectNode customerNotes = object(metadataTools().describeTable("dbo", "customer_notes"));
        ArrayNode triggers = (ArrayNode) field(customerNotes, "triggers");
        ObjectNode trigger = (ObjectNode) findByField(triggers, "name", "customer_notes_touch_trg");
        assertThat(trigger).isNotNull();
        assertThat(textValues((ArrayNode) field(trigger, "events"))).contains("INSERT", "UPDATE");

        String definition = metadataTools().getTriggerDefinition(
                "dbo", "customer_notes", "customer_notes_touch_trg");
        assertThat(definition).contains("CREATE TRIGGER").contains("customer_notes_touch_trg");
    }

    @Test
    void listsRoutinesSequencesAndSearchResults() {
        ArrayNode routines = array(metadataTools().listRoutines("dbo", "customer_count%").routines());
        assertThat(findByField(routines, "name", "customer_count_fn")).isNotNull();

        String source = metadataTools().getRoutineDefinition("dbo", "customer_count_fn");
        assertThat(source).contains("COUNT(*)").contains("customers");

        ArrayNode sequences = array(metadataTools().listSequences("dbo").sequences());
        assertThat(findByField(sequences, "name", "audit_seq")).isNotNull();

        ArrayNode search = array(metadataTools().searchObjects("customer").objects());
        assertThat(findByField(search, "name", "customers")).isNotNull();
        assertThat(findByField(search, "name", "v_customer_totals")).isNotNull();
    }
}
