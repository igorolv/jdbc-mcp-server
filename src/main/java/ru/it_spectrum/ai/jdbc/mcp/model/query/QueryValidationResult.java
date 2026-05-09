package ru.it_spectrum.ai.jdbc.mcp.model.query;

public record QueryValidationResult(
        boolean valid,
        Integer parameters,
        Integer columns,
        String stage,
        String error,
        QueryInspection inspection
) {
    public static QueryValidationResult valid(int parameters, int columns, QueryInspection inspection) {
        return new QueryValidationResult(true, parameters, columns, null, null, inspection);
    }

    public static QueryValidationResult invalid(String stage, String error, QueryInspection inspection) {
        return new QueryValidationResult(false, null, null, stage, error, inspection);
    }
}
