package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import ru.it_spectrum.ai.jdbc.mcp.model.JsonKey;

public record JoinCardinality(
        @JsonKey("from_schema") String fromSchema,
        @JsonKey("from_table") String fromTable,
        @JsonKey("left_column") String leftColumn,
        @JsonKey("from_row_estimate") Long fromRowEstimate,
        @JsonKey("to_schema") String toSchema,
        @JsonKey("to_table") String toTable,
        @JsonKey("right_column") String rightColumn,
        @JsonKey("to_row_estimate") Long toRowEstimate,
        @JsonKey("join_type") String joinType,
        @JsonKey("estimated_rows") Long estimatedRows,
        @JsonKey("cartesian_rows") Long cartesianRows,
        @JsonKey("selectivity_vs_cartesian") Double selectivityVsCartesian,
        String note
) {}
