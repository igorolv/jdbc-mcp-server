package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationSnapshotToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void tableContextHitsCacheOnRepeatedCall() {
        snapshotTools().invalidateSnapshot(null, null);

        schemaContextTools().tableContext("public", "orders", 1, true, false, false);

        ObjectNode afterFirst = object(snapshotTools().getSchemaSnapshot("public"));

        schemaContextTools().tableContext("public", "orders", 1, true, false, false);

        ObjectNode afterSecond = object(snapshotTools().getSchemaSnapshot("public"));

        ArrayNode schemas = (ArrayNode) field(afterSecond, "schemas");
        JsonNode pub = findByField(schemas, "schema", "public");
        assertThat(pub)
                .as("snapshot info must include the cached 'public' schema entry")
                .isNotNull();
        ArrayNode cachedTables = (ArrayNode) field(pub, "tables");
        assertThat(textValues((ArrayNode) toNames(cachedTables)))
                .contains("customers", "orders", "line_items");
    }

    @Test
    void invalidateForcesReFetch() {
        snapshotTools().invalidateSnapshot(null, null);
        schemaContextTools().tableContext("public", "orders", 1, true, false, false);

        ObjectNode invalidated = object(snapshotTools().invalidateSnapshot("public", null));
        assertThat(field(invalidated, "invalidated").asText()).isEqualTo("schema");

        schemaContextTools().tableContext("public", "orders", 1, true, false, false);

        ObjectNode afterReWarm = object(snapshotTools().getSchemaSnapshot("public"));
        ArrayNode schemas = (ArrayNode) field(afterReWarm, "schemas");
        JsonNode pub = findByField(schemas, "schema", "public");
        assertThat(pub)
                .as("cache must be repopulated after invalidation")
                .isNotNull();
    }

    @Test
    void refreshSchemaSnapshotEagerlyWarmsCache() {
        snapshotTools().invalidateSnapshot(null, null);

        ObjectNode result = object(snapshotTools().refreshSchemaSnapshot("public", null, 50));
        assertThat(field(result, "enabled").asBoolean()).isTrue();

        ObjectNode info = object(snapshotTools().getSchemaSnapshot("public"));
        ArrayNode schemas = (ArrayNode) field(info, "schemas");
        JsonNode pub = findByField(schemas, "schema", "public");
        assertThat(pub).isNotNull();
        ArrayNode cachedTables = (ArrayNode) field(pub, "tables");
        assertThat(cachedTables.size())
                .as("cache must hold every described table after refresh")
                .isGreaterThanOrEqualTo(4);
    }

    private JsonNode toNames(ArrayNode cachedTables) {
        com.fasterxml.jackson.databind.node.ArrayNode names = new com.fasterxml.jackson.databind.node.ArrayNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        for (JsonNode node : cachedTables) {
            JsonNode name = node.get("table");
            if (name != null) names.add(name.asText());
        }
        return names;
    }
}
