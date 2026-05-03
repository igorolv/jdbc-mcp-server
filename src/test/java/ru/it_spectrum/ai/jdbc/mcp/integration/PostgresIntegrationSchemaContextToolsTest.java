package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationSchemaContextToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void returnsCompactSchemaOverview() {
        ObjectNode overview = object(schemaContextTools().schemaOverview(
                "public", "%", true, false, true, 20));

        assertThat(field(overview, "truncated").asBoolean()).isFalse();
        ArrayNode tables = (ArrayNode) field(overview, "tables");
        assertThat(findByField(tables, "name", "customers")).isNotNull();
        assertThat(findByField(tables, "name", "orders")).isNotNull();

        ArrayNode relationships = (ArrayNode) field(overview, "relationships");
        assertThat(relationship(relationships, "orders", "customers")).isNotNull();
        JsonNode inferred = relationship(relationships, "customer_notes", "customers");
        assertThat(inferred).isNotNull();
        assertThat(field(inferred, "relationshipType").asText()).isEqualTo("inferred");
    }

    @Test
    void returnsTableNeighborhood() {
        ObjectNode context = object(schemaContextTools().tableContext(
                "public", "orders", 1, true, false, true, 100));

        ArrayNode tables = (ArrayNode) field(context, "tables");
        assertThat(findByField(tables, "name", "orders")).isNotNull();
        assertThat(findByField(tables, "name", "customers")).isNotNull();
        assertThat(findByField(tables, "name", "line_items")).isNotNull();

        ArrayNode relationships = (ArrayNode) field(context, "relationships");
        assertThat(relationship(relationships, "orders", "customers")).isNotNull();
        assertThat(relationship(relationships, "line_items", "orders")).isNotNull();
    }

    @Test
    void findsFkJoinPath() {
        ObjectNode result = object(schemaContextTools().findJoinPaths(
                "public", "line_items", "public", "customers", 4, 5, 100, true));

        assertThat(field(result, "pathCount").asInt()).isGreaterThanOrEqualTo(1);
        ArrayNode paths = (ArrayNode) field(result, "paths");
        ArrayNode firstPath = (ArrayNode) paths.get(0);
        assertThat(firstPath).hasSize(2);
        assertThat(field(firstPath.get(0), "joinCondition").asText()).contains("line_items.order_id");
        assertThat(field(firstPath.get(1), "joinCondition").asText()).contains("orders.customer_id");
    }

    @Test
    void findsInferredJoinPathWithoutDeclaredFk() {
        ObjectNode result = object(schemaContextTools().findJoinPaths(
                "public", "customer_notes", "public", "customers", 2, 5, 100, true));

        assertThat(field(result, "pathCount").asInt()).isGreaterThanOrEqualTo(1);
        ArrayNode paths = (ArrayNode) field(result, "paths");
        ArrayNode firstPath = (ArrayNode) paths.get(0);
        assertThat(firstPath).hasSize(1);
        assertThat(field(firstPath.get(0), "relationshipType").asText()).isEqualTo("inferred");
        assertThat(field(firstPath.get(0), "joinCondition").asText()).contains("customer_notes.customer_id");
    }

    private JsonNode relationship(ArrayNode relationships, String fromTable, String toTable) {
        for (JsonNode edge : relationships) {
            if (fromTable.equals(field(edge, "fromTable").asText())
                    && toTable.equals(field(edge, "toTable").asText())) {
                return edge;
            }
        }
        return null;
    }
}
