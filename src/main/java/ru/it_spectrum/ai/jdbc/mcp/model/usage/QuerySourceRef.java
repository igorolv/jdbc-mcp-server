package ru.it_spectrum.ai.jdbc.mcp.model.usage;

public record QuerySourceRef(
        String sourceKind,
        String sourcePath,
        String sourceUnit
) {
    public QuerySourceRef {
        if (sourceKind == null || sourceKind.isBlank()) {
            throw new IllegalArgumentException("sourceKind is required");
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("sourcePath is required");
        }
    }

    public String sourceUnitNormalized() {
        return sourceUnit == null ? "" : sourceUnit;
    }
}
