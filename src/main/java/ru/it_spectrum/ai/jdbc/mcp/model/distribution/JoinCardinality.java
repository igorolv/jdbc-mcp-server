package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

public record JoinCardinality(
        String fromSchema,
        String fromTable,
        String leftColumn,
        Long fromRowEstimate,
        String toSchema,
        String toTable,
        String rightColumn,
        Long toRowEstimate,
        String joinType,
        Long estimatedRows,
        Long cartesianRows,
        Double selectivityVsCartesian,
        String note
) {}
