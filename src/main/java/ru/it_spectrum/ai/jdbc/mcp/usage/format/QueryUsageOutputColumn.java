package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record QueryUsageOutputColumn(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Schema name of the underlying physical column. Optional when unknown or when the column is unqualified in the SQL.")
        String schema,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Table name of the underlying physical column. Optional when the output is computed from constants/expressions only.")
        String table,
        @JsonPropertyDescription("Physical column name the output is derived from.")
        String column
) {
}
