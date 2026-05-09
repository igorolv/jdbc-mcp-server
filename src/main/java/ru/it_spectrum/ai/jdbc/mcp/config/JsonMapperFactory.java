package ru.it_spectrum.ai.jdbc.mcp.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class JsonMapperFactory {

    private JsonMapperFactory() {
    }

    public static ObjectMapper create() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }
}
