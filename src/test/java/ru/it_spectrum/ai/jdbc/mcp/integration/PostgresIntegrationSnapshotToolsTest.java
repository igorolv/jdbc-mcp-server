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
    void schemaOverviewHitsCacheOnRepeatedCall() {
        snapshotTools().invalidateSnapshot(null, null);

        ObjectNode baseline = object(snapshotTools().getSchemaSnapshot("public"));
        long baseDescribeMisses = field(baseline, "describeMisses").asLong();
        long baseDescribeHits = field(baseline, "describeHits").asLong();
        long baseListMisses = field(baseline, "listMisses").asLong();
        long baseListHits = field(baseline, "listHits").asLong();

        schemaContextTools().schemaOverview("public", "%", true, false, true, false, 50);

        ObjectNode afterFirst = object(snapshotTools().getSchemaSnapshot("public"));
        long firstDescribeMisses = field(afterFirst, "describeMisses").asLong();
        long firstListMisses = field(afterFirst, "listMisses").asLong();
        assertThat(firstDescribeMisses - baseDescribeMisses)
                .as("first call must populate the describe cache")
                .isPositive();
        assertThat(firstListMisses - baseListMisses)
                .as("first call must populate the listTables cache")
                .isEqualTo(1L);

        schemaContextTools().schemaOverview("public", "%", true, false, true, false, 50);

        ObjectNode afterSecond = object(snapshotTools().getSchemaSnapshot("public"));
        long secondDescribeMisses = field(afterSecond, "describeMisses").asLong();
        long secondDescribeHits = field(afterSecond, "describeHits").asLong();
        long secondListMisses = field(afterSecond, "listMisses").asLong();
        long secondListHits = field(afterSecond, "listHits").asLong();

        assertThat(secondDescribeMisses)
                .as("second call must not produce additional describe misses")
                .isEqualTo(firstDescribeMisses);
        assertThat(secondDescribeHits - baseDescribeHits)
                .as("second call must hit the describe cache")
                .isPositive();
        assertThat(secondListMisses)
                .as("second call must not re-fetch listTables")
                .isEqualTo(firstListMisses);
        assertThat(secondListHits - baseListHits)
                .as("second call must hit the listTables cache")
                .isEqualTo(1L);

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
        schemaContextTools().schemaOverview("public", "%", true, false, true, false, 50);

        ObjectNode warm = object(snapshotTools().getSchemaSnapshot("public"));
        long warmDescribeMisses = field(warm, "describeMisses").asLong();
        long warmListMisses = field(warm, "listMisses").asLong();

        ObjectNode invalidated = object(snapshotTools().invalidateSnapshot("public", null));
        assertThat(field(invalidated, "invalidated").asText()).isEqualTo("schema");

        schemaContextTools().schemaOverview("public", "%", true, false, true, false, 50);

        ObjectNode afterReWarm = object(snapshotTools().getSchemaSnapshot("public"));
        assertThat(field(afterReWarm, "describeMisses").asLong())
                .as("invalidating the schema must force re-describe on next call")
                .isGreaterThan(warmDescribeMisses);
        assertThat(field(afterReWarm, "listMisses").asLong())
                .as("invalidating the schema must drop the list cache too")
                .isGreaterThan(warmListMisses);
    }

    @Test
    void refreshSchemaSnapshotEagerlyWarmsCache() {
        snapshotTools().invalidateSnapshot(null, null);

        ObjectNode result = object(snapshotTools().refreshSchemaSnapshot("public", null, 50));
        assertThat(field(result, "refreshedTables").asInt())
                .as("refresh must describe at least the seeded tables")
                .isGreaterThanOrEqualTo(4);
        assertThat(field(result, "errors").asInt()).isZero();
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
