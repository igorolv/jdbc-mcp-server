package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationSchemaContextToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void returnsTableNeighborhood() {
        ObjectNode context = object(schemaContextTools().tableContext(
                "public", "orders", 1, true, false, false));

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
                "public", "line_items", "public", "customers", 4, 5, 100, false));

        assertThat(field(result, "pathCount").asInt()).isGreaterThanOrEqualTo(1);
        ArrayNode paths = (ArrayNode) field(result, "paths");
        ArrayNode firstPath = (ArrayNode) paths.get(0);
        assertThat(firstPath).hasSize(2);
        assertThat(field(firstPath.get(0), "joinCondition").asText()).contains("line_items.order_id");
        assertThat(field(firstPath.get(1), "joinCondition").asText()).contains("orders.customer_id");
    }

    @Test
    void lintsSchemaForSqlAuthoringRisks() {
        ObjectNode result = object(schemaContextTools().schemaLint(
                "public", null, null, 100, 200));

        ArrayNode findings = (ArrayNode) field(result, "findings");
        assertThat(finding(findings, "fkWithoutIndex", "orders", "customer_id")).isNotNull();
        assertThat(finding(findings, "orphanIdColumn", "customer_notes", "customer_id")).isNotNull();
        assertThat(finding(findings, "missingTableRemarks", "orders", null)).isNotNull();
    }

    @Test
    void returnsCompactSchemaBrief() {
        String brief = schemaContextTools().schemaBrief("public", null, 100);

        assertThat(brief).contains("Schema brief");
        assertThat(brief).contains("Tables");
        assertThat(brief).contains("customers [TABLE]");
        assertThat(brief).contains("orders [TABLE]");
        assertThat(brief).contains("keys id");
        assertThat(brief).contains("Key relationships");
        assertThat(brief).contains("orders.customer_id -> customers.id");
    }

    @Test
    void returnsRelationshipGraphMetrics() {
        ObjectNode graph = object(schemaContextTools().schemaGraph(
                "public", 100, "line_items", "customers", 4));

        assertThat(field(graph, "nodeCount").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(field(graph, "declaredEdgeCount").asInt()).isEqualTo(2);

        ArrayNode central = (ArrayNode) field(graph, "centralTables");
        assertThat(findByField(central, "table", "customers")).isNotNull();

        ArrayNode isolated = (ArrayNode) field(graph, "isolatedTables");
        assertThat(findByField(isolated, "table", "events")).isNotNull();

        ObjectNode shortestPath = (ObjectNode) field(graph, "shortestPath");
        assertThat(field(shortestPath, "found").asBoolean()).isTrue();
        assertThat(field(shortestPath, "edges")).hasSize(2);
    }

    @Test
    void returnsQueryAuthoringContext() {
        ObjectNode context = object(schemaContextTools().queryContext(
                "public", "customer orders total", "customers,orders", true, 10));

        ArrayNode tables = (ArrayNode) field(context, "tables");
        ObjectNode customers = (ObjectNode) findByField(tables, "name", "customers");
        ObjectNode orders = (ObjectNode) findByField(tables, "name", "orders");
        assertThat(customers).isNotNull();
        assertThat(orders).isNotNull();
        assertThat(findByField((ArrayNode) field(orders, "relevantColumns"), "name", "total")).isNotNull();
        assertThat(field(field(customers, "sample"), "rowCount").asInt()).isGreaterThan(0);

        ArrayNode relationships = (ArrayNode) field(context, "relationships");
        assertThat(relationship(relationships, "orders", "customers")).isNotNull();

        ArrayNode joinPaths = (ArrayNode) field(context, "joinPaths");
        assertThat(joinPaths).isNotEmpty();
        assertThat(field(joinPaths.get(0), "found").asBoolean()).isTrue();
    }

    @Test
    void returnsSchemaGraphDot() {
        String dot = schemaContextTools().schemaGraphDot("public", null);

        assertThat(dot).startsWith("digraph");
        assertThat(dot).contains("customers");
        assertThat(dot).contains("orders");
        assertThat(dot).contains("->");
        assertThat(dot).contains("style=solid");
        assertThat(dot).contains("orders.customer_id = customers.id");
    }

    @Test
    void returnsSchemaGraphDotFiltered() {
        String dot = schemaContextTools().schemaGraphDot("public", "customers,orders");

        assertThat(dot).startsWith("digraph");
        assertThat(dot).contains("customers");
        assertThat(dot).contains("orders");
        assertThat(dot).doesNotContain("line_items");
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

    private JsonNode finding(ArrayNode findings, String check, String table, String column) {
        for (JsonNode finding : findings) {
            if (!check.equals(field(finding, "check").asText())) continue;
            if (!table.equals(field(finding, "table").asText())) continue;
            JsonNode findingColumn = finding.get("column");
            if (column == null || (findingColumn != null && column.equals(findingColumn.asText()))) {
                return finding;
            }
        }
        return null;
    }
}
