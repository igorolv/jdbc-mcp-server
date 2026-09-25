package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.plan.FirebirdPlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.OraclePlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.PostgresPlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.SqlitePlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.SqlServerPlanParser;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

@Configuration
public class DialectConfig {

    /** Generic JDBC reads the database's traits through the connection's pool on first use. */
    @Bean
    public SqlDialect sqlDialect(DatabaseKind kind, ObjectProvider<DataSource> dataSource) {
        return kind == DatabaseKind.GENERIC
                ? new GenericDialect(dataSource.getObject())
                : SqlDialect.forKind(kind);
    }

    @Bean
    public PlanParser planParser(DatabaseKind kind, ObjectMapper mapper) {
        return switch (kind) {
            case POSTGRESQL -> new PostgresPlanParser(mapper);
            case ORACLE -> new OraclePlanParser();
            case MSSQL -> new SqlServerPlanParser();
            case FIREBIRD -> new FirebirdPlanParser();
            case SQLITE -> new SqlitePlanParser();
            case GENERIC -> (result, analyzed) -> {
                throw new UnsupportedFeatureException("Generic JDBC connections have no execution plans");
            };
        };
    }
}
