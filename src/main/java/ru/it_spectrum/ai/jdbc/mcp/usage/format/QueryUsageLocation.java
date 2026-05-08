package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Map;

public record QueryUsageLocation(
        @JsonPropertyDescription("Where the value is rendered: 'excel-cell', 'rtf-region', 'ui-label', 'dashboard-widget', etc. Free-text.")
        String kind,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Arbitrary key/value details about the location (e.g. {sheet:'Summary', cell:'B12'} or {widgetId:'lblName'}). Stored as JSON.")
        Map<String, Object> details
) {
}
