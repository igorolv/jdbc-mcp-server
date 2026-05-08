package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record QueryUsageParameter(
        @JsonPropertyDescription("Bind name (without ':' / '?'). Used to match caller-supplied semantics to parser-detected bindings; falls back to ordinal when absent on either side.")
        String name,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Declared SQL data type as a free-text label (e.g. 'NUMBER', 'VARCHAR2(50)', 'date').")
        String dataType,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Default value as a string, if any.")
        String defaultValue,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Whether the parameter is mandatory in the consuming artifact.")
        Boolean required,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Short business label of the parameter (UI prompt, report parameter caption).")
        String businessLabel,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Longer business description: meaning, allowed values, source of the value.")
        String businessDescription
) {
}
