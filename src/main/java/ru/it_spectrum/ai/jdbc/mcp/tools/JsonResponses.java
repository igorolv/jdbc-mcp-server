package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonResponses {

    private final ObjectMapper mapper;

    public JsonResponses(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize JSON response", e);
        }
    }
}
