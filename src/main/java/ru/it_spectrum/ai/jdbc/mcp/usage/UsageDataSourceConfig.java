package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import org.springframework.core.io.ClassPathResource;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Builds the H2 in-memory {@link DataSource} used as the runtime usage-catalog index.
 *
 * <p>The bean is published unconditionally to keep wiring simple; whether the catalog actually
 * accepts ingest requests is decided by {@link UsageProperties#catalogEnabled()} inside the
 * service layer.
 *
 * <p>The source of truth is the configured set of query-usage JSON files. This database is only a
 * rebuildable in-memory index over those files.
 */
@Configuration
@EnableConfigurationProperties(UsageProperties.class)
public class UsageDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(UsageDataSourceConfig.class);

    private static final String SCHEMA_RESOURCE = "usage-catalog-schema.sql";

    @Bean
    public DataSource usageDataSource(UsageProperties properties, JdbcMcpProperties jdbcMcpProperties)
            throws IOException, SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:usage_catalog_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");

        initialiseSchema(ds);

        log.info("Active catalog: {} (dir: {})",
                jdbcMcpProperties.resolvedCatalogName(), jdbcMcpProperties.catalogDir());
        log.info("Usage catalog runtime index ready (enabled={}, sources={})",
                properties.catalogEnabled(), jdbcMcpProperties.usageCatalogDir());
        return ds;
    }

    private void initialiseSchema(DataSource ds) throws IOException, SQLException {
        String ddl = readSchema();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(ddl)) {
                if (statement.isBlank()) continue;
                stmt.execute(statement);
            }
        }
    }

    private static String readSchema() throws IOException {
        try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String[] splitStatements(String ddl) {
        String stripped = stripLineComments(ddl);
        return stripped.split(";\\s*\\R*");
    }

    private static String stripLineComments(String ddl) {
        StringBuilder out = new StringBuilder(ddl.length());
        for (String line : ddl.split("\\R", -1)) {
            int idx = line.indexOf("--");
            String content = idx < 0 ? line : line.substring(0, idx);
            out.append(content).append('\n');
        }
        return out.toString();
    }
}
