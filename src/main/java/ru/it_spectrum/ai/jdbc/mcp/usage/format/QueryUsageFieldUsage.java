package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public record QueryUsageFieldUsage(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Output alias from the 'outputs' list this usage is for. May be null when the usage is not tied to a specific output column.")
        String output,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Business object / area / screen where the value is displayed (free-text grouping aid).")
        String businessObject,
        @JsonPropertyDescription("Required. How the value is transformed before display.")
        QueryUsageTransformation transformation,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Where in the consuming artifact the value is rendered.")
        QueryUsageLocation location,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Surrounding header/label text near the rendered value (helps the agent reconstruct the visual context).")
        List<String> headers,
        @JsonProperty(required = false)
        @JsonPropertyDescription("How confident the caller is in this usage record.")
        QueryUsageConfidence confidence
) {
}
