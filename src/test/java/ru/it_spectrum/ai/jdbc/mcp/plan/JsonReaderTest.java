package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonReaderTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesObjectsAndArrays() {
        Object v = JsonReader.parse("""
                { "a": 1, "b": "x", "c": [true, false, null], "d": { "nested": 3.5 } }
                """);
        assertThat(v).isInstanceOf(Map.class);
        Map<String, Object> m = (Map<String, Object>) v;
        assertThat(m.get("a")).isEqualTo(1L);
        assertThat(m.get("b")).isEqualTo("x");
        assertThat((List<Object>) m.get("c")).containsExactly(true, false, null);
        assertThat(((Map<String, Object>) m.get("d")).get("nested")).isEqualTo(3.5);
    }

    @Test
    void parsesEscapes() {
        Object v = JsonReader.parse("\"a\\nb\\\\c\\\"d\\u0041\"");
        assertThat(v).isEqualTo("a\nb\\c\"dA");
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThatThrownBy(() -> JsonReader.parse("{}junk"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
