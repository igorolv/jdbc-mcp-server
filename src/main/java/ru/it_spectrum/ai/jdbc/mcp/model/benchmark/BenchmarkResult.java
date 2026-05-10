package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import java.util.List;

public record BenchmarkResult(
        String engine,
        int runs,
        int coldRuns,
        int warmRuns,
        int limit,
        int timeoutSeconds,
        TimingStats coldMs,
        TimingStats warmMs,
        List<Double> allMs,
        ResultSize resultSize,
        String note
) {
}