package ru.it_spectrum.ai.jdbc.mcp.format;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultFormatterTest {

    private QueryResult sample() {
        List<String> cols = List.of("id", "name");
        List<String> types = List.of("int", "text");
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("id", 1);
        r1.put("name", "Alice");
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("id", 2);
        r2.put("name", "Bo\"b");
        return new QueryResult(cols, types, List.of(r1, r2), false, 2);
    }

    @Test
    void jsonPreservesColumnOrder() {
        String out = ResultFormatter.toJson(sample());
        assertThat(out).contains("\"columns\":[\"id\",\"name\"]");
        assertThat(out.indexOf("\"id\":1")).isLessThan(out.indexOf("\"name\":\"Alice\""));
    }

    @Test
    void jsonEscapesQuotes() {
        String out = ResultFormatter.toJson(sample());
        assertThat(out).contains("\"name\":\"Bo\\\"b\"");
    }

    @Test
    void jsonReportsTruncation() {
        QueryResult truncated = new QueryResult(
                List.of("x"), List.of("int"), List.of(Map.of("x", 1)), true, 1);
        assertThat(ResultFormatter.toJson(truncated)).contains("\"truncated\":true");
    }

    @Test
    void markdownRendersTable() {
        String out = ResultFormatter.toMarkdown(sample());
        assertThat(out).contains("| id | name |");
        assertThat(out).contains("| 1 | Alice |");
        assertThat(out).contains("rows: 2");
    }

    @Test
    void markdownEscapesPipe() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", "x|y");
        QueryResult r = new QueryResult(
                List.of("a"), List.of("text"), List.of(row), false, 1);
        assertThat(ResultFormatter.toMarkdown(r)).contains("x\\|y");
    }

    @Test
    void csvQuotesValuesWithSpecials() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", "hello, world");
        row.put("b", "line\nbreak");
        QueryResult r = new QueryResult(
                List.of("a", "b"), List.of("text", "text"), List.of(row), false, 1);
        String out = ResultFormatter.toCsv(r);
        assertThat(out).contains("\"hello, world\"");
        assertThat(out).contains("\"line\nbreak\"");
    }
}
