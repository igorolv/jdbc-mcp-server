package ru.it_spectrum.ai.jdbc.mcp.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonConfig {

    @Bean
    @Primary
    public ObjectMapper jdbcMcpObjectMapper() {
        // Jackson 3 mapper. WRITE_DATES_AS_TIMESTAMPS is off by default in J3 (moved to
        // DateTimeFeature), so it is no longer set explicitly. SORT_PROPERTIES_ALPHABETICALLY
        // is *on* by default in J3 — disabled here to preserve the existing key order our
        // tests and MCP responses rely on.
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }
}
