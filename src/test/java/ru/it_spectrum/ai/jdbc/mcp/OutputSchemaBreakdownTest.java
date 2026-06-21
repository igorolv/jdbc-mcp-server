package ru.it_spectrum.ai.jdbc.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Diagnostic: decomposes the generated MCP output-schema bytes into description prose,
 * the {@code "null"} type-union cost contributed by {@code nullable=true}, the
 * {@code required[]} arrays, and everything else (pure structure). Also reports whether
 * the generator reuses nested types via {@code $defs}/{@code $ref}.
 */
class OutputSchemaBreakdownTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void breakdown() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        var candidates = scanner.findCandidateComponents("ru.it_spectrum.ai.jdbc.mcp.tools");

        long total = 0, descBytes = 0, nullUnionBytes = 0, requiredBytes = 0;
        int nullableFields = 0, nullableTypedFields = 0, defsTools = 0, descCount = 0;

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
                total += schema.length();
                if (schema.contains("$defs") || schema.contains("$ref")) defsTools++;

                JsonNode root = M.readTree(schema);
                long[] acc = new long[4]; // desc, nullUnion, required, (counters packed separately)
                int[] cnt = new int[3];  // nullableFields, nullableTyped, descCount
                walk(root, acc, cnt);
                descBytes += acc[0];
                nullUnionBytes += acc[1];
                requiredBytes += acc[2];
                nullableFields += cnt[0];
                nullableTypedFields += cnt[1];
                descCount += cnt[2];
            }
        }

        long structure = total - descBytes - nullUnionBytes - requiredBytes;
        System.out.println("\n===== OUTPUT SCHEMA BREAKDOWN =====");
        System.out.printf("TOTAL chars                : %d%n", total);
        System.out.printf("description text + keys    : %d (%.1f%%)  across %d fields%n",
                descBytes, pct(descBytes, total), descCount);
        System.out.printf("nullable null-union cost   : %d (%.1f%%)  %d nullable fields, %d with concrete type%n",
                nullUnionBytes, pct(nullUnionBytes, total), nullableFields, nullableTypedFields);
        System.out.printf("required[] arrays          : %d (%.1f%%)%n", requiredBytes, pct(requiredBytes, total));
        System.out.printf("everything else (structure): %d (%.1f%%)%n", structure, pct(structure, total));
        System.out.printf("tools using $defs/$ref     : %d of 48%n", defsTools);
        System.out.println("===================================\n");
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }

    /** Walks the schema tree accumulating byte costs. */
    private static void walk(JsonNode node, long[] acc, int[] cnt) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String key = e.getKey();
                JsonNode v = e.getValue();
                if (key.equals("description") && v.isTextual()) {
                    // value text + the "description": "" wrapper overhead
                    acc[0] += v.asText().length() + "\"description\": \"\"".length();
                    cnt[2]++;
                } else if (key.equals("required") && v.isArray()) {
                    acc[2] += v.toString().length() + "\"required\": ".length();
                } else if (key.equals("type") && v.isArray()) {
                    boolean hasNull = false;
                    for (JsonNode el : v) if ("null".equals(el.asText())) hasNull = true;
                    if (hasNull) {
                        cnt[0]++;
                        if (v.size() > 1) {
                            cnt[1]++;
                            // marginal cost vs a scalar type: the array brackets + , "null"
                            acc[1] += ", \"null\"".length() + "[  ]".length();
                        }
                    }
                }
                walk(v, acc, cnt);
            }
        } else if (node.isArray()) {
            for (JsonNode el : node) walk(el, acc, cnt);
        }
    }
}
