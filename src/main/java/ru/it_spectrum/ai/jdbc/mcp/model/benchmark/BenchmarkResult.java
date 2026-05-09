package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import java.util.List;

public record BenchmarkResult(
        String engine,
        int runs,
        int cold_runs,
        int warm_runs,
        int limit,
        int timeout_seconds,
        TimingStats cold_ms,
        TimingStats warm_ms,
        List<Double> all_ms,
        ResultSize result_size,
        String note
) {
}
