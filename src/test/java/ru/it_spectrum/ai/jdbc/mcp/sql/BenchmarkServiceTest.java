package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-logic unit tests for {@link BenchmarkService#toStats(List)}. */
class BenchmarkServiceTest {

    @Test
    void emptySamplesReturnsNullStats() {
        Map<String, Object> s = BenchmarkService.toStats(List.of());
        assertThat(s.get("runs")).isEqualTo(0);
        assertThat(s.get("min")).isNull();
        assertThat(s.get("median")).isNull();
        assertThat(s.get("max")).isNull();
    }

    @Test
    void oddSampleSizeUsesMiddleElementAsMedian() {
        Map<String, Object> s = BenchmarkService.toStats(List.of(3.0, 1.0, 2.0));
        assertThat(((Number) s.get("runs")).intValue()).isEqualTo(3);
        assertThat(((Number) s.get("min")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) s.get("median")).doubleValue()).isEqualTo(2.0);
        assertThat(((Number) s.get("max")).doubleValue()).isEqualTo(3.0);
    }

    @Test
    void evenSampleSizeAveragesMiddleTwoForMedian() {
        // sorted: 1, 2, 3, 4 → median = (2 + 3) / 2 = 2.5
        Map<String, Object> s = BenchmarkService.toStats(List.of(4.0, 1.0, 3.0, 2.0));
        assertThat(((Number) s.get("min")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) s.get("median")).doubleValue()).isEqualTo(2.5);
        assertThat(((Number) s.get("max")).doubleValue()).isEqualTo(4.0);
    }

    @Test
    void roundingKeepsOneDecimalPlace() {
        Map<String, Object> s = BenchmarkService.toStats(List.of(1.2345, 9.8765));
        // rounded to one decimal place in reported output
        assertThat(((Number) s.get("min")).doubleValue()).isEqualTo(1.2);
        assertThat(((Number) s.get("max")).doubleValue()).isEqualTo(9.9);
    }
}
