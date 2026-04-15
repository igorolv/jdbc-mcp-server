package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

@Configuration
public class DialectConfig {

    @Bean
    public SqlDialect sqlDialect(DatabaseKind kind) {
        return switch (kind) {
            case POSTGRESQL -> new PostgresDialect();
            case ORACLE -> new OracleDialect();
        };
    }
}
