package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.it_spectrum.ai.jdbc.mcp.tools.BenchmarkTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.DistributionTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.MetadataTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.QueryTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SampleTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SchemaContextTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SnapshotTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.StatsTools;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

abstract class AbstractToolsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    protected abstract IntegrationTestContext context();

    protected final String schema() {
        return context().schema();
    }

    protected final QueryTools queryTools() {
        return context().queryTools();
    }

    protected final MetadataTools metadataTools() {
        return context().metadataTools();
    }

    protected final SampleTools sampleTools() {
        return context().sampleTools();
    }

    protected final StatsTools statsTools() {
        return context().statsTools();
    }

    protected final SchemaContextTools schemaContextTools() {
        return context().schemaContextTools();
    }

    protected final DistributionTools distributionTools() {
        return context().distributionTools();
    }

    protected final BenchmarkTools benchmarkTools() {
        return context().benchmarkTools();
    }

    protected final SnapshotTools snapshotTools() {
        return context().snapshotTools();
    }

    protected final ObjectNode object(String json) {
        JsonNode node = parse(json);
        assertThat(node.isObject()).isTrue();
        return (ObjectNode) node;
    }

    protected final ArrayNode array(String json) {
        JsonNode node = parse(json);
        assertThat(node.isArray()).isTrue();
        return (ArrayNode) node;
    }

    protected final ArrayNode rows(String json) {
        return rows(object(json));
    }

    protected final ArrayNode rows(ObjectNode json) {
        JsonNode rows = json.get("rows");
        assertThat(rows).isNotNull();
        assertThat(rows.isArray()).isTrue();
        return (ArrayNode) rows;
    }

    protected final JsonNode row(ObjectNode json, int index) {
        return rows(json).get(index);
    }

    protected final JsonNode field(JsonNode node, String name) {
        JsonNode value = maybeField(node, name);
        assertThat(value)
                .withFailMessage("Expected field '%s' in node: %s", name, node)
                .isNotNull();
        return value;
    }

    protected final JsonNode findByField(ArrayNode array, String fieldName, String expected) {
        for (JsonNode node : array) {
            JsonNode field = maybeField(node, fieldName);
            if (field != null && expected.equals(field.asText())) {
                return node;
            }
        }
        return null;
    }

    protected final List<String> textValues(ArrayNode array) {
        List<String> out = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            out.add(node.asText());
        }
        return out;
    }

    protected final void assertRejected(String response, String expectedFragment) {
        assertThat(response).startsWith("Rejected:");
        assertThat(response).contains(expectedFragment);
    }

    protected final void assertInvalidArgument(String response, String expectedFragment) {
        assertThat(response).startsWith("Invalid argument:");
        assertThat(response).contains(expectedFragment);
    }

    private JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Expected JSON response but got: " + json, e);
        }
    }

    private JsonNode maybeField(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode direct = node.get(name);
        if (direct != null) {
            return direct;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
