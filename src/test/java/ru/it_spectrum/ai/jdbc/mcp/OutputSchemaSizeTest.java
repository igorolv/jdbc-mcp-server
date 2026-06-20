package ru.it_spectrum.ai.jdbc.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
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
 * Diagnostic (not an assertion test): measures the total size of the generated MCP output schemas
 * across every {@code @McpTool} method that has {@code generateOutputSchema = true}. Run with:
 * {@code ./gradlew test --tests OutputSchemaSizeTest -i} and read the printed report.
 */
class OutputSchemaSizeTest {

    record Row(String tool, String returnType, int chars) {}

    @Test
    void measureOutputSchemaSizes() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        var candidates = scanner.findCandidateComponents("ru.it_spectrum.ai.jdbc.mcp.tools");

        List<Row> rows = new ArrayList<>();
        int total = 0;
        for (var bd : candidates) {
            Class<?> cls;
            try {
                cls = Class.forName(((AnnotatedBeanDefinition) bd).getMetadata().getClassName());
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Method m : cls.getDeclaredMethods()) {
                McpTool t = m.getAnnotation(McpTool.class);
                if (t == null || !t.generateOutputSchema()) continue;
                Type rt = m.getGenericReturnType();
                if (rt == void.class) continue;
                String schema = JsonSchemaGenerator.generateForType(rt);
                int len = schema.length();
                total += len;
                rows.add(new Row(m.getName(), rt.getTypeName(), len));
            }
        }

        rows.sort(Comparator.comparingInt(Row::chars).reversed());
        System.out.println("\n===== OUTPUT SCHEMA SIZE REPORT =====");
        System.out.printf("tools with output schema: %d%n", rows.size());
        System.out.printf("TOTAL output-schema chars: %d%n%n", total);
        System.out.printf("%-9s  %s%n", "chars", "tool");
        for (Row r : rows) {
            System.out.printf("%-9d  %s%n", r.chars(), r.tool());
        }
        System.out.println("=====================================\n");
    }
}
