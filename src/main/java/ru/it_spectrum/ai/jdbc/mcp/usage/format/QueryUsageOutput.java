package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public record QueryUsageOutput(
        @JsonPropertyDescription("Output column alias as it appears in the SELECT list (case-sensitive).")
        String alias,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Original SQL expression that produced this output, copied verbatim from the SELECT clause.")
        String sourceExpression,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Short business label of the output (column header, field caption).")
        String businessLabel,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Longer business description of the output.")
        String businessDescription,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Physical columns that the output is derived from. Empty/absent when the output is constant or computed from non-column expressions only.")
        List<QueryUsageOutputColumn> derivedFromColumns
) {
}
