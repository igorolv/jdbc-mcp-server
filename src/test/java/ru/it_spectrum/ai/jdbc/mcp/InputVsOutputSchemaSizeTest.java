package ru.it_spectrum.ai.jdbc.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Diagnostic (not an assertion test): for every {@code @McpTool} method, measures the size of the
 * generated MCP <em>input</em> schema (what every MCP client puts in the model context at
 * {@code tools/list} time) alongside the <em>output</em> schema. Prints per-tool and totals so we
 * can see which one actually drives context bloat. Run with:
 * {@code ./gradlew test --tests InputVsOutputSchemaSizeTest -i} and read the printed report.
 */
class InputVsOutputSchemaSizeTest {

    record Row(String tool, int descChars, int inChars, int outChars) {
        int promptChars() { return descChars + inChars; }
    }

    @Test
    void measureInputVsOutputSchemaSizes() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        var candidates = scanner.findCandidateComponents("ru.it_spectrum.ai.jdbc.mcp.tools");

        List<Row> rows = new ArrayList<>();
        int totalDesc = 0;
        int totalIn = 0;
        int totalOut = 0;
        for (var bd : candidates) {
            Class<?> cls;
            try {
                cls = Class.forName(((AnnotatedBeanDefinition) bd).getMetadata().getClassName());
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Method m : cls.getDeclaredMethods()) {
                McpTool t = m.getAnnotation(McpTool.class);
                if (t == null) continue;

                int descLen = t.description().length();
                // McpJsonSchemaGenerator is the generator the MCP server actually uses to build the
                // advertised inputSchema: it reads @McpToolParam (descriptions included) and honours
                // required=false. JsonSchemaGenerator.generateForMethodInput does NOT — wrong tool here.
                int inLen = McpJsonSchemaGenerator.generateForMethodInput(m).length();

                int outLen = 0;
                Type rt = m.getGenericReturnType();
                if (t.generateOutputSchema() && rt != void.class) {
                    outLen = JsonSchemaGenerator.generateForType(rt).length();
                }

                totalDesc += descLen;
                totalIn += inLen;
                totalOut += outLen;
                rows.add(new Row(m.getName(), descLen, inLen, outLen));
            }
        }

        rows.sort(Comparator.comparingInt(Row::promptChars).reversed());
        System.out.println("\n===== INPUT vs OUTPUT SCHEMA SIZE REPORT =====");
        System.out.printf("tools: %d%n", rows.size());
        System.out.printf("TOTAL tool-description chars: %d%n", totalDesc);
        System.out.printf("TOTAL input-schema chars    : %d%n", totalIn);
        System.out.printf("=> in-context per connect   : %d (desc + input)%n", totalDesc + totalIn);
        System.out.printf("TOTAL output-schema chars   : %d%n%n", totalOut);
        System.out.printf("%-7s  %-7s  %-7s  %s%n", "desc", "in", "out", "tool");
        for (Row r : rows) {
            System.out.printf("%-7d  %-7d  %-7d  %s%n", r.descChars(), r.inChars(), r.outChars(), r.tool());
        }
        System.out.println("==============================================\n");
    }
}
