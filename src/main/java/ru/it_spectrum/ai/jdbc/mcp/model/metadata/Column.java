package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Column(
        String name,
        int ordinalPosition,
        String typeName,
        int size,
        Integer decimalDigits,
        boolean nullable,
        @JsonProperty("default") String defaultValue,
        String remarks,
        Boolean autoIncrement
) {
}
