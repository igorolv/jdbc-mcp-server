package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record QueryUsageSource(
        @JsonPropertyDescription("Free-text classifier of the artifact this query lives in (e.g. 'bi-publisher-report', 'dao', 'dashboard').")
        String kind,
        @JsonPropertyDescription("Stable source identifier (file, repository path, URL, dashboard id, adapter-defined id). Must not contain '#'.")
        String path,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Sub-unit inside the artifact: dataset name, DAO method, dashboard panel id. Optional. Must not contain '/' or '#'.")
        String unit
) {
}
