package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class OracleIntegrationSchemaContextToolsTest extends AbstractOracleToolsIntegrationTest {

    @Test
    void returnsCompactSchemaOverview() {
        ObjectNode overview = object(schemaContextTools().schemaOverview(
                schema(), "%", true, false, false, 20));

        assertThat(field(overview, "truncated").asBoolean()).isFalse();
        ArrayNode tables = (ArrayNode) field(overview, "tables");
        assertThat(findByField(tables, "name", "CUSTOMERS")).isNotNull();
        assertThat(findByField(tables, "name", "ORDERS")).isNotNull();

        ArrayNode relationships = (ArrayNode) field(overview, "relationships");
        assertThat(relationship(relationships, "ORDERS", "CUSTOMERS")).isNotNull();
        assertThat(relationship(relationships, "CUSTOMER_NOTES", "CUSTOMERS")).isNull();
    }

    @Test
    void returnsTableNeighborhood() {
        ObjectNode context = object(schemaContextTools().tableContext(
                schema(), "ORDERS", 1, true, false, false));

        ArrayNode tables = (ArrayNode) field(context, "tables");
        assertThat(findByField(tables, "name", "ORDERS")).isNotNull();
        assertThat(findByField(tables, "name", "CUSTOMERS")).isNotNull();
        assertThat(findByField(tables, "name", "LINE_ITEMS")).isNotNull();

        ArrayNode relationships = (ArrayNode) field(context, "relationships");
        assertThat(relationship(relationships, "ORDERS", "CUSTOMERS")).isNotNull();
        assertThat(relationship(relationships, "LINE_ITEMS", "ORDERS")).isNotNull();
    }

    @Test
    void findsFkJoinPath() {
        ObjectNode result = object(schemaContextTools().findJoinPaths(
                schema(), "LINE_ITEMS", schema(), "CUSTOMERS", 4, 5, 100, false));

        assertThat(field(result, "pathCount").asInt()).isGreaterThanOrEqualTo(1);
        ArrayNode paths = (ArrayNode) field(result, "paths");
        ArrayNode firstPath = (ArrayNode) paths.get(0);
        assertThat(firstPath).hasSize(2);
        assertThat(field(firstPath.get(0), "joinCondition").asText()).contains("LINE_ITEMS.ORDER_ID");
        assertThat(field(firstPath.get(1), "joinCondition").asText()).contains("ORDERS.CUSTOMER_ID");
    }

    @Test
    void lintsSchemaForSqlAuthoringRisks() {
        ObjectNode result = object(schemaContextTools().schemaLint(
                schema(), null, null, 100, 200));

        ArrayNode findings = (ArrayNode) field(result, "findings");
        assertThat(finding(findings, "fkWithoutIndex", "ORDERS", "CUSTOMER_ID")).isNotNull();
        assertThat(finding(findings, "orphanIdColumn", "CUSTOMER_NOTES", "CUSTOMER_ID")).isNotNull();
        assertThat(finding(findings, "missingTableRemarks", "ORDERS", null)).isNotNull();
    }

    @Test
    void returnsCompactSchemaBrief() {
        String brief = schemaContextTools().schemaBrief(schema(), null, 100);

        assertThat(brief).contains("Schema brief");
        assertThat(brief).contains("Key relationships");
        assertThat(brief).contains("ORDERS.CUSTOMER_ID -> CUSTOMERS.ID");
        assertThat(brief).contains("Enum-like columns");
        assertThat(brief).contains("EVENTS.status in [OK, FAIL]");
    }

    @Test
    void returnsRelationshipGraphMetrics() {
        ObjectNode graph = object(schemaContextTools().schemaGraph(
                schema(), 100, "LINE_ITEMS", "CUSTOMERS", 4));

        assertThat(field(graph, "nodeCount").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(field(graph, "declaredEdgeCount").asInt()).isEqualTo(2);

        ArrayNode central = (ArrayNode) field(graph, "centralTables");
        assertThat(findByField(central, "table", "CUSTOMERS")).isNotNull();

        ArrayNode isolated = (ArrayNode) field(graph, "isolatedTables");
        assertThat(findByField(isolated, "table", "EVENTS")).isNotNull();

        ObjectNode shortestPath = (ObjectNode) field(graph, "shortestPath");
        assertThat(field(shortestPath, "found").asBoolean()).isTrue();
        assertThat(field(shortestPath, "edges")).hasSize(2);
    }

    @Test
    void returnsQueryAuthoringContext() {
        ObjectNode context = object(schemaContextTools().queryContext(
                schema(), "customer orders total", "CUSTOMERS,ORDERS", true, 10));

        ArrayNode tables = (ArrayNode) field(context, "tables");
        ObjectNode customers = (ObjectNode) findByField(tables, "name", "CUSTOMERS");
        ObjectNode orders = (ObjectNode) findByField(tables, "name", "ORDERS");
        assertThat(customers).isNotNull();
        assertThat(orders).isNotNull();
        assertThat(findByField((ArrayNode) field(orders, "relevantColumns"), "name", "TOTAL")).isNotNull();
        assertThat(field(field(customers, "sample"), "rowCount").asInt()).isGreaterThan(0);

        ArrayNode relationships = (ArrayNode) field(context, "relationships");
        assertThat(relationship(relationships, "ORDERS", "CUSTOMERS")).isNotNull();

        ArrayNode joinPaths = (ArrayNode) field(context, "joinPaths");
        assertThat(joinPaths).isNotEmpty();
        assertThat(field(joinPaths.get(0), "found").asBoolean()).isTrue();
    }

    @Test
    void returnsSchemaGraphDot() {
        String dot = schemaContextTools().schemaGraphDot(schema(), null);

        assertThat(dot).startsWith("digraph");
        assertThat(dot).contains("CUSTOMERS");
        assertThat(dot).contains("ORDERS");
        assertThat(dot).contains("->");
        assertThat(dot).contains("style=solid");
        assertThat(dot).contains("ORDERS.CUSTOMER_ID = CUSTOMERS.ID");
    }

    @Test
    void returnsSchemaGraphDotFiltered() {
        String dot = schemaContextTools().schemaGraphDot(schema(), "CUSTOMERS,ORDERS");

        assertThat(dot).startsWith("digraph");
        assertThat(dot).contains("CUSTOMERS");
        assertThat(dot).contains("ORDERS");
        assertThat(dot).doesNotContain("LINE_ITEMS");
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
