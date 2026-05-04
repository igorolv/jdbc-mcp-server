package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.plan.OraclePlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.PostgresPlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.SqlServerPlanParser;

@Configuration
public class DialectConfig {

    @Bean
    public SqlDialect sqlDialect(DatabaseKind kind) {
        return switch (kind) {
            case POSTGRESQL -> new PostgresDialect();
            case ORACLE -> new OracleDialect();
            case MSSQL -> new SqlServerDialect();
        };
    }

    @Bean
    public PlanParser planParser(DatabaseKind kind) {
        return switch (kind) {
            case POSTGRESQL -> new PostgresPlanParser();
            case ORACLE -> new OraclePlanParser();
            case MSSQL -> new SqlServerPlanParser();
        };
    }
}
