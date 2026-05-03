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
                schema(), "%", true, false, true, 20));

        assertThat(field(overview, "truncated").asBoolean()).isFalse();
        ArrayNode tables = (ArrayNode) field(overview, "tables");
        assertThat(findByField(tables, "name", "CUSTOMERS")).isNotNull();
        assertThat(findByField(tables, "name", "ORDERS")).isNotNull();

        ArrayNode relationships = (ArrayNode) field(overview, "relationships");
        assertThat(relationship(relationships, "ORDERS", "CUSTOMERS")).isNotNull();
        JsonNode inferred = relationship(relationships, "CUSTOMER_NOTES", "CUSTOMERS");
        assertThat(inferred).isNotNull();
        assertThat(field(inferred, "relationshipType").asText()).isEqualTo("inferred");
    }

    @Test
    void returnsTableNeighborhood() {
        ObjectNode context = object(schemaContextTools().tableContext(
                schema(), "ORDERS", 1, true, false, true, 100));

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
                schema(), "LINE_ITEMS", schema(), "CUSTOMERS", 4, 5, 100, true));

        assertThat(field(result, "pathCount").asInt()).isGreaterThanOrEqualTo(1);
        ArrayNode paths = (ArrayNode) field(result, "paths");
        ArrayNode firstPath = (ArrayNode) paths.get(0);
        assertThat(firstPath).hasSize(2);
        assertThat(field(firstPath.get(0), "joinCondition").asText()).contains("LINE_ITEMS.ORDER_ID");
        assertThat(field(firstPath.get(1), "joinCondition").asText()).contains("ORDERS.CUSTOMER_ID");
    }

    @Test
    void findsInferredJoinPathWithoutDeclaredFk() {
        ObjectNode result = object(schemaContextTools().findJoinPaths(
                schema(), "CUSTOMER_NOTES", schema(), "CUSTOMERS", 2, 5, 100, true));

        assertThat(field(result, "pathCount").asInt()).isGreaterThanOrEqualTo(1);
        ArrayNode paths = (ArrayNode) field(result, "paths");
        ArrayNode firstPath = (ArrayNode) paths.get(0);
        assertThat(firstPath).hasSize(1);
        assertThat(field(firstPath.get(0), "relationshipType").asText()).isEqualTo("inferred");
        assertThat(field(firstPath.get(0), "joinCondition").asText()).contains("CUSTOMER_NOTES.CUSTOMER_ID");
    }

    @Test
    void lintsSchemaForSqlAuthoringRisks() {
        ObjectNode result = object(schemaContextTools().schemaLint(
                schema(), null, null, 100, 200, true));

        ArrayNode findings = (ArrayNode) field(result, "findings");
        assertThat(finding(findings, "fkWithoutIndex", "ORDERS", "CUSTOMER_ID")).isNotNull();
        assertThat(finding(findings, "inferredRelationship", "CUSTOMER_NOTES", "CUSTOMER_ID")).isNotNull();
        assertThat(finding(findings, "orphanIdColumn", "CUSTOMER_NOTES", "CUSTOMER_ID")).isNotNull();
        assertThat(finding(findings, "missingTableRemarks", "ORDERS", null)).isNotNull();
    }

    @Test
    void returnsCompactSchemaBrief() {
        String brief = schemaContextTools().schemaBrief(schema(), null, 100, true);

        assertThat(brief).contains("Schema brief");
        assertThat(brief).contains("Hub tables");
        assertThat(brief).contains("Key relationships");
        assertThat(brief).contains("ORDERS.CUSTOMER_ID -> CUSTOMERS.ID");
        assertThat(brief).contains("Suspicious implicit joins");
        assertThat(brief).contains("CUSTOMER_NOTES.CUSTOMER_ID -> CUSTOMERS.ID");
        assertThat(brief).contains("Enum-like columns");
        assertThat(brief).contains("EVENTS.status in [OK, FAIL]");
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
