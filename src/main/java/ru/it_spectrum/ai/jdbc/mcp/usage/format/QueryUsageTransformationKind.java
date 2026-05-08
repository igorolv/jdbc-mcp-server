package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum QueryUsageTransformationKind {
    IDENTITY, AGGREGATE, DERIVED, CONDITIONAL, FILTER, FORMAT, DECODE, OTHER;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static QueryUsageTransformationKind fromJson(String value) {
        if (value == null) return null;
        return QueryUsageTransformationKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
