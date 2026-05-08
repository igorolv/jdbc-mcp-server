package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum QueryUsageConfidence {
    HIGH, MEDIUM, LOW;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static QueryUsageConfidence fromJson(String value) {
        if (value == null) return null;
        return QueryUsageConfidence.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
