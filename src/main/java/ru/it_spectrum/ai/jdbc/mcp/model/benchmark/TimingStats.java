package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

public record TimingStats(
        int runs,
        Double min,
        Double median,
        Double max
) {
}
