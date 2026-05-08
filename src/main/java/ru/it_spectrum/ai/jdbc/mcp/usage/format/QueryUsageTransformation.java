package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record QueryUsageTransformation(
        @JsonPropertyDescription("How the value is transformed before being shown. 'identity' = passed through unchanged.")
        QueryUsageTransformationKind kind,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Free-text description of the transformation (formula, formatting, mapping rule).")
        String description
) {
}
