package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputSchemaSmokeTest {

    private static final Set<String> TEXT_TOOLS = Set.of(
            "explainQuery",
            "getTriggerDefinition",
            "getViewDefinition",
            "getRoutineDefinition",
            "schemaBrief",
            "schemaGraphDot"
    );

    @Test
    void allMcpToolsRequestOutputSchemasAndReadOnlyHints() {
        for (Class<?> toolClass : toolClasses()) {
            for (Method method : toolClass.getDeclaredMethods()) {
                McpTool tool = method.getAnnotation(McpTool.class);
                if (tool == null) {
                    continue;
                }
                assertThat(tool.generateOutputSchema())
                        .as("%s.%s should request output schema generation",
                                toolClass.getSimpleName(), method.getName())
                        .isTrue();
                assertThat(tool.annotations().readOnlyHint())
                        .as("%s.%s should be marked read-only",
                                toolClass.getSimpleName(), method.getName())
                        .isTrue();
                assertThat(tool.annotations().destructiveHint())
                        .as("%s.%s should be marked non-destructive",
                                toolClass.getSimpleName(), method.getName())
                        .isFalse();
                assertThat(tool.annotations().idempotentHint())
                        .as("%s.%s should be marked idempotent",
                                toolClass.getSimpleName(), method.getName())
                        .isTrue();
            }
        }
    }

    @Test
    void structuredToolsReturnObjectsInsteadOfJsonStrings() {
        for (Class<?> toolClass : toolClasses()) {
            for (Method method : toolClass.getDeclaredMethods()) {
                if (method.getAnnotation(McpTool.class) == null || TEXT_TOOLS.contains(method.getName())) {
                    continue;
                }
                assertThat(method.getReturnType())
                        .as("%s.%s should return a structured DTO/list, not a JSON string",
                                toolClass.getSimpleName(), method.getName())
                        .isNotEqualTo(String.class);
            }
        }
    }

    @Test
    void schemaGeneratorCanDescribeRepresentativeResponses() {
        String tableSchema = McpJsonSchemaGenerator.generateFromClass(TableDescription.class);
        assertThat(tableSchema).contains("\"columns\"");
        assertThat(tableSchema).contains("\"indexes\"");
        assertThat(tableSchema).contains("Column list in database order");
        assertThat(tableSchema).doesNotContain("TableDescription response payload");
        assertThat(tableSchema).contains("[ \"array\", \"null\" ]");

        String querySchema = McpJsonSchemaGenerator.generateFromClass(QueryResult.class);
        assertThat(querySchema).contains("\"rows\"");
        assertThat(querySchema).contains("\"truncated\"");
        assertThat(querySchema).contains("[ \"array\", \"null\" ]");
    }

    @Test
    void representativeDtoComponentsDeclareRequiredAndNullable() throws NoSuchFieldException {
        // Optional fields: NOT_REQUIRED keeps them out of the schema's required[],
        // nullable=true lets the type accept null values when a backend returns them.
        Schema schema = schemaFor(TableDescription.class, "schema");
        assertThat(schema.requiredMode()).isEqualTo(Schema.RequiredMode.NOT_REQUIRED);
        assertThat(schema.nullable()).isTrue();

        Schema rows = schemaFor(QueryResult.class, "rows");
        assertThat(rows.requiredMode()).isEqualTo(Schema.RequiredMode.NOT_REQUIRED);
        assertThat(rows.nullable()).isTrue();

        // Conceptually-required primitives keep requiredMode=REQUIRED as an LLM hint;
        // nullable=true still admits an absent value during validation.
        Schema truncated = schemaFor(QueryResult.class, "truncated");
        assertThat(truncated.requiredMode()).isEqualTo(Schema.RequiredMode.REQUIRED);
        assertThat(truncated.nullable()).isTrue();
    }

    private static Schema schemaFor(Class<?> type, String name) throws NoSuchFieldException {
        RecordComponent component = component(type, name);
        Schema schema = component.getAnnotation(Schema.class);
        if (schema != null) {
            return schema;
        }
        schema = component.getAccessor().getAnnotation(Schema.class);
        if (schema != null) {
            return schema;
        }
        Field field = type.getDeclaredField(name);
        return field.getAnnotation(Schema.class);
    }

    private static RecordComponent component(Class<?> type, String name) throws NoSuchFieldException {
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Class<?>[] toolClasses() {
        return new Class<?>[]{
                QueryTools.class,
                BenchmarkTools.class,
                MetadataTools.class,
                AdminTools.class,
                SampleTools.class,
                DistributionTools.class,
                StatsTools.class,
                SchemaContextTools.class,
                UsageTools.class
        };
    }
}
