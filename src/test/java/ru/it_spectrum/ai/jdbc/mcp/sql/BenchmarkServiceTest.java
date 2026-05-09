package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.model.benchmark.TimingStats;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-logic unit tests for {@link BenchmarkService#toStats(List)}. */
class BenchmarkServiceTest {

    @Test
    void emptySamplesReturnsNullStats() {
        TimingStats s = BenchmarkService.toStats(List.of());
        assertThat(s.runs()).isEqualTo(0);
        assertThat(s.min()).isNull();
        assertThat(s.median()).isNull();
        assertThat(s.max()).isNull();
    }

    @Test
    void oddSampleSizeUsesMiddleElementAsMedian() {
        TimingStats s = BenchmarkService.toStats(List.of(3.0, 1.0, 2.0));
        assertThat(s.runs()).isEqualTo(3);
        assertThat(s.min()).isEqualTo(1.0);
        assertThat(s.median()).isEqualTo(2.0);
        assertThat(s.max()).isEqualTo(3.0);
    }

    @Test
    void evenSampleSizeAveragesMiddleTwoForMedian() {
        // sorted: 1, 2, 3, 4 → median = (2 + 3) / 2 = 2.5
        TimingStats s = BenchmarkService.toStats(List.of(4.0, 1.0, 3.0, 2.0));
        assertThat(s.min()).isEqualTo(1.0);
        assertThat(s.median()).isEqualTo(2.5);
        assertThat(s.max()).isEqualTo(4.0);
    }

    @Test
    void roundingKeepsOneDecimalPlace() {
        TimingStats s = BenchmarkService.toStats(List.of(1.2345, 9.8765));
        // rounded to one decimal place in reported output
        assertThat(s.min()).isEqualTo(1.2);
        assertThat(s.max()).isEqualTo(9.9);
    }
}
