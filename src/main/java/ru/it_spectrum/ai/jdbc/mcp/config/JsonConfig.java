package ru.it_spectrum.ai.jdbc.mcp.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonConfig {

    /**
     * Declared as {@link JsonMapper}, not {@link ObjectMapper}: with the HTTP transport on the class
     * path, Spring Boot's Jackson auto-configuration adds a primary {@code JsonMapper} of its own
     * unless one exists, and two primary mappers fail every {@code ObjectMapper} injection.
     */
    @Bean
    @Primary
    public JsonMapper jdbcMcpObjectMapper() {
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

    /**
     * Overrides Spring AI's autoconfigured {@code mcpServerJsonMapper}
     * ({@code McpServerJsonMapperAutoConfiguration#mcpServerJsonMapper}, guarded by
     * {@code @ConditionalOnMissingBean(name = "mcpServerJsonMapper")}).
     *
     * <p>The default bean configures {@code JsonInclude.Value.construct(NON_NULL, NON_NULL)},
     * which strips {@code null} values inside {@code Map} arguments during JSON-RPC
     * {@code CallToolRequest} conversion — turning {@code namedParams: {"d": null}} into
     * {@code {}} before the tool method is invoked. Changing only {@code contentInclusion} to
     * {@code ALWAYS} preserves {@code null} map entries ({@code namedParams} and SQL result
     * rows) while keeping {@code null} DTO fields omitted on the wire.
     */
    @Bean(name = "mcpServerJsonMapper")
    public JsonMapper mcpServerJsonMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .addModules(JacksonUtils.instantiateAvailableModules())
                .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }
}
