package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationMetadataToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void listsSchemasAndTables() {
        assertThat(textValues(array(metadataTools().listSchemas(false)))).contains("public");

        ArrayNode tables = rows(metadataTools().listTables("public", "%", null));
        assertThat(findByField(tables, "name", "customers")).isNotNull();
        assertThat(findByField(tables, "name", "v_customer_totals")).isNotNull();
    }

    @Test
    void describesTableAndViewDefinition() {
        ObjectNode table = object(metadataTools().describeTable("public", "orders"));
        assertThat(field(table, "name").asText()).isEqualTo("orders");
        assertThat(findByField((ArrayNode) field(table, "columns"), "name", "customer_id")).isNotNull();
        assertThat(field(field(table, "primaryKey"), "name").asText()).isEqualTo("orders_pkey");

        String viewDefinition = metadataTools().getViewDefinition("public", "v_customer_totals");
        assertThat(viewDefinition).containsIgnoringCase("FROM customers");
    }

    @Test
    void listsRoutinesSequencesAndSearchResults() {
        ArrayNode routines = rows(metadataTools().listRoutines("public", "customer_count%"));
        assertThat(findByField(routines, "name", "customer_count_fn")).isNotNull();

        String source = metadataTools().getRoutineDefinition("public", "customer_count_fn");
        assertThat(source).contains("COUNT(*)").contains("customers");

        ArrayNode sequences = rows(metadataTools().listSequences("public"));
        assertThat(findByField(sequences, "name", "audit_seq")).isNotNull();

        ArrayNode search = rows(metadataTools().searchObjects("customer"));
        assertThat(findByField(search, "name", "customers")).isNotNull();
        assertThat(findByField(search, "name", "v_customer_totals")).isNotNull();
    }
}
