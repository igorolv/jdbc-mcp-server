package ru.it_spectrum.ai.jdbc.mcp.config;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerJsonMapperTest {

    @Test
    void callToolRequestConversionPreservesNullNamedParams() {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new JsonConfig().mcpServerJsonMapper());

        Map<String, Object> onlyNull = new LinkedHashMap<>();
        onlyNull.put("d", null);

        McpSchema.CallToolRequest onlyNullRequest = mapper.convertValue(
                Map.of(
                        "name", "executeQuery",
                        "arguments", Map.of(
                                "connection", "c",
                                "sql", "SELECT :d",
                                "namedParams", onlyNull)),
                McpSchema.CallToolRequest.class);

        assertThat(onlyNullRequest.arguments().get("namedParams"))
                .isInstanceOfSatisfying(Map.class, namedParams -> {
                    assertThat(namedParams).containsKey("d");
                    assertThat(namedParams.get("d")).isNull();
                });

        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("a", 1);
        mixed.put("d", null);

        McpSchema.CallToolRequest mixedRequest = mapper.convertValue(
                Map.of(
                        "name", "executeQuery",
                        "arguments", Map.of(
                                "connection", "c",
                                "sql", "SELECT :a, :d",
                                "namedParams", mixed)),
                McpSchema.CallToolRequest.class);

        assertThat(mixedRequest.arguments().get("namedParams"))
                .isInstanceOfSatisfying(Map.class, namedParams -> {
                    assertThat(namedParams.get("a")).isEqualTo(1);
                    assertThat(namedParams).containsKey("d");
                    assertThat(namedParams.get("d")).isNull();
                });
    }

    @Test
    void dtoNullFieldsAreStillOmittedWhileMapNullEntriesSurviveSerialization() {
        JsonMapper mapper = new JsonConfig().mcpServerJsonMapper();

        String dtoJson = mapper.writeValueAsString(new TableEntry("S", "T", "TABLE", null));
        assertThat(dtoJson).doesNotContain("\"remarks\"");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("col", null);
        String mapJson = mapper.writeValueAsString(row);
        assertThat(mapJson).contains("\"col\":null");
    }

    @Test
    void springContextUsesCustomMcpServerJsonMapperOverAutoconfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(JsonConfig.class)
                .withConfiguration(AutoConfigurations.of(McpServerJsonMapperAutoConfiguration.class))
                .withPropertyValues("spring.ai.mcp.server.stdio=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("jdbcMcpObjectMapper");
                    assertThat(context).hasBean("mcpServerJsonMapper");
                    assertThat(context.getBean(JsonMapper.class))
                            .isSameAs(context.getBean("jdbcMcpObjectMapper", JsonMapper.class));

                    JsonMapper serverMapper = context.getBean("mcpServerJsonMapper", JsonMapper.class);
                    JacksonMcpJsonMapper mcpMapper = new JacksonMcpJsonMapper(serverMapper);

                    Map<String, Object> namedParams = new LinkedHashMap<>();
                    namedParams.put("d", null);

                    McpSchema.CallToolRequest request = mcpMapper.convertValue(
                            Map.of(
                                    "name", "executeQuery",
                                    "arguments", Map.of(
                                            "connection", "c",
                                            "sql", "SELECT :d",
                                            "namedParams", namedParams)),
                            McpSchema.CallToolRequest.class);

                    assertThat(request.arguments().get("namedParams"))
                            .isInstanceOfSatisfying(Map.class, parsedNamed -> {
                                assertThat(parsedNamed).containsKey("d");
                                assertThat(parsedNamed.get("d")).isNull();
                            });
                });
    }
}
