package ru.it_spectrum.ai.jdbc.mcp.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.ai.util.json.JsonParser;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ListTablesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the @Schema convention used by every record under {@code model/}: optional
 * fields must declare both {@code nullable = true} and {@code requiredMode = NOT_REQUIRED}.
 *
 * <p>Why both:
 * <ul>
 *   <li>Spring AI's MCP schema generator treats a missing {@code requiredMode} as AUTO
 *       == REQUIRED, so optional fields land in the schema's {@code required[]}.</li>
 *   <li>The autoconfigured {@code mcpServerJsonMapper} bean serialises with
 *       {@link JsonInclude.Include#NON_NULL}, stripping null values from the wire JSON.</li>
 *   <li>Combined, an Oracle table with no comment would emit no {@code remarks} key,
 *       failing schema validation with "must have required property 'remarks'".</li>
 * </ul>
 * The test reproduces that failure path against a faithful copy of the autoconfigured
 * server mapper and asserts that the updated annotations make validation pass.
 */
class OutputSchemaSmokeTest {

    private static final JsonMapper SERVER_MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .addModules(JacksonUtils.instantiateAvailableModules())
            .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    private static final DefaultJsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

    @Test
    void tableEntryOmitsRemarksFromRequiredAndAllowsNullType() {
        String schemaJson = McpJsonSchemaGenerator.generateFromClass(TableEntry.class);

        // remarks must still appear as a property...
        assertThat(schemaJson).contains("\"remarks\"");
        // ...but never inside a required[] array, otherwise the NON_NULL-stripped wire JSON
        // would fail validation with "must have required property 'remarks'".
        assertThat(schemaJson)
                .as("remarks must not appear in any required[] array")
                .doesNotMatch("(?s).*\"required\"\\s*:\\s*\\[[^\\]]*\"remarks\"[^\\]]*\\].*");
        // Optional string fields collapse to `[ "string", "null" ]`.
        assertThat(schemaJson).contains("[ \"string\", \"null\" ]");
    }

    @Test
    void listTablesResultPassesValidationWithNullRemarks() throws Exception {
        ListTablesResult sample = new ListTablesResult(List.of(
                new TableEntry("FOO", "BAR", "TABLE", null),
                new TableEntry("FOO", "BAZ", "TABLE")));

        String wireJson = SERVER_MAPPER.writeValueAsString(sample);
        // Sanity: NON_NULL stripping is in effect — remarks is gone from each entry.
        assertThat(wireJson).doesNotContain("\"remarks\"");

        Object parsed = SERVER_MAPPER.readValue(wireJson, Object.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> schemaMap = JsonParser.fromJson(
                McpJsonSchemaGenerator.generateFromClass(ListTablesResult.class), Map.class);

        var result = VALIDATOR.validate(schemaMap, parsed);
        assertThat(result.valid())
                .as("validation error: %s", result.errorMessage())
                .isTrue();
    }
}
