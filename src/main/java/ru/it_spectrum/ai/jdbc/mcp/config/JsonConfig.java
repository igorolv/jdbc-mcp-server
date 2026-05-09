package ru.it_spectrum.ai.jdbc.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class JsonConfig {

    @Bean
    @Primary
    public ObjectMapper jdbcMcpObjectMapper() {
        return JsonMapperFactory.create();
    }
}
