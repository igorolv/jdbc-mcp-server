package ru.it_spectrum.ai.jdbc.mcp.tools;

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.ai.util.JacksonUtils;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParamType;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParamTypeEntry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryToolsInputSchemaTest {

    private static final List<String> EXPECTED_ENUM =
            List.of("date", "timestamp", "timestamptz", "uuid", "integer", "decimal");

    private static final DefaultJsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();
    private static final JsonMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    @Test
    void allSixQueryToolsEmitNativeStrictEnumArraySchemaForParamTypes() throws Exception {
        for (Method method : sixToolMethods()) {
            String schemaJson = McpJsonSchemaGenerator.generateForMethodInput(method);
            JsonNode root = MAPPER.readTree(schemaJson);

            assertThat(root.path("required").toString())
                    .as("%s should keep paramTypes optional", method.getName())
                    .doesNotContain("paramTypes");

            JsonNode paramTypesNode = root.path("properties").path("paramTypes");
            assertThat(paramTypesNode.path("type").asText())
                    .as("%s.paramTypes schema:\n%s", method.getName(), schemaJson)
                    .isEqualTo("array");

            JsonNode itemSchema = resolveSchema(root, paramTypesNode.path("items"));
            assertThat(itemSchema.path("type").asText()).isEqualTo("object");

            List<String> itemRequired = new ArrayList<>();
            itemSchema.path("required").forEach(n -> itemRequired.add(n.asText()));
            assertThat(itemRequired)
                    .as("%s.paramTypes.items.required:\n%s", method.getName(), schemaJson)
                    .containsExactlyInAnyOrder("param", "type");

            JsonNode itemProps = itemSchema.path("properties");
            assertThat(itemProps.path("param").path("type").asText()).isEqualTo("string");

            JsonNode typeSchema = resolveSchema(root, itemProps.path("type"));
            assertThat(typeSchema.path("type").asText()).isEqualTo("string");
            assertThat(typeSchema.has("allOf")).isFalse();

            List<String> enumValues = new ArrayList<>();
            typeSchema.path("enum").forEach(n -> enumValues.add(n.asText()));
            assertThat(enumValues)
                    .as("%s.paramTypes.items.properties.type schema:\n%s", method.getName(), schemaJson)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_ENUM);
        }
    }

    @Test
    void validatorAndJacksonEnforceStrictSixEnumValues() throws Exception {
        Method executeQuery = QueryTools.class.getMethod("executeQuery",
                String.class, String.class, List.class, Map.class, List.class, Integer.class, Integer.class);
        String schemaJson = McpJsonSchemaGenerator.generateForMethodInput(executeQuery);
        Map<String, Object> schemaMap = MAPPER.readValue(schemaJson, new TypeReference<>() {
        });

        assertThat(VALIDATOR.validate(schemaMap, Map.of(
                "connection", "c",
                "sql", "SELECT 1")).valid()).isTrue();

        for (String allowedType : EXPECTED_ENUM) {
            assertThat(VALIDATOR.validate(schemaMap, Map.of(
                    "connection", "c",
                    "sql", "SELECT :p",
                    "namedParams", Map.of("p", "val"),
                    "paramTypes", List.of(Map.of("param", "p", "type", allowedType)))).valid())
                    .as("type '%s' should pass schema validation", allowedType)
                    .isTrue();
        }

        for (String disallowedType : List.of("VARCHAR2", "NUMBER(10)", "boolean", "string", "datetime")) {
            assertThat(VALIDATOR.validate(schemaMap, Map.of(
                    "connection", "c",
                    "sql", "SELECT :p",
                    "namedParams", Map.of("p", "val"),
                    "paramTypes", List.of(Map.of("param", "p", "type", disallowedType)))).valid())
                    .as("type '%s' must be rejected by schema validation", disallowedType)
                    .isFalse();
        }

        assertThat(VALIDATOR.validate(schemaMap, Map.of(
                "connection", "c",
                "sql", "SELECT :p",
                "paramTypes", List.of(Map.of("param", "p")))).valid())
                .as("missing 'type' must fail validation")
                .isFalse();

        assertThat(VALIDATOR.validate(schemaMap, Map.of(
                "connection", "c",
                "sql", "SELECT :p",
                "paramTypes", List.of(Map.of("type", "date")))).valid())
                .as("missing 'param' must fail validation")
                .isFalse();

        List<SqlParamTypeEntry> converted = MAPPER.convertValue(
                List.of(
                        Map.of("param", "dt", "type", "timestamp"),
                        Map.of("param", "d", "type", "DATE")),
                new TypeReference<>() {
                });
        assertThat(converted).containsExactly(
                new SqlParamTypeEntry("dt", SqlParamType.timestamp),
                new SqlParamTypeEntry("d", SqlParamType.date));

        assertThatThrownBy(() -> MAPPER.convertValue(
                List.of(Map.of("param", "dt", "type", "VARCHAR2")),
                new TypeReference<List<SqlParamTypeEntry>>() {
                }))
                .isInstanceOf(RuntimeException.class);
    }

    private static List<Method> sixToolMethods() throws NoSuchMethodException {
        return List.of(
                QueryTools.class.getMethod("executeQuery",
                        String.class, String.class, List.class, Map.class, List.class, Integer.class, Integer.class),
                QueryAnalysisTools.class.getMethod("explainQuery",
                        String.class, String.class, List.class, Map.class, List.class, Boolean.class),
                QueryAnalysisTools.class.getMethod("analyzePlan",
                        String.class, String.class, List.class, Map.class, List.class, Boolean.class),
                QueryAnalysisTools.class.getMethod("validateQuery",
                        String.class, String.class, List.class, Map.class, List.class),
                BenchmarkTools.class.getMethod("benchmarkQuery",
                        String.class, String.class, List.class, Map.class, List.class,
                        Integer.class, Integer.class, Integer.class, Integer.class),
                BenchmarkTools.class.getMethod("timedQuery",
                        String.class, String.class, List.class, Map.class, List.class, Integer.class, Integer.class)
        );
    }

    private static JsonNode resolveSchema(JsonNode root, JsonNode node) {
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            if (ref.startsWith("#/$defs/")) {
                return root.path("$defs").path(ref.substring("#/$defs/".length()));
            }
        }
        return node;
    }
}
