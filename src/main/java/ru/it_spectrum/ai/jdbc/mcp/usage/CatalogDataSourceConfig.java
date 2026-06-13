package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Builds the single persistent H2 {@link DataSource} backing this catalog: it holds both the
 * usage-catalog index and the persistent structure snapshot in one {@code <catalog>.db} file under
 * {@code <data-dir>/<catalog>/}.
 *
 * <p>Both {@link UsageCatalogService} and {@code H2StructureSnapshotStore} consume this one
 * DataSource (bean name {@code usageDataSource}, kept for backwards-compatible wiring). On
 * initialisation both DDL scripts are applied idempotently ({@code CREATE TABLE IF NOT EXISTS}),
 * so opening an existing file is safe.
 *
 * <p>The structure snapshot is authoritative until explicitly invalidated or the file is deleted
 * ("cache forever"). {@code AUTO_SERVER=TRUE} lets several MCP servers / harnesses open the same
 * file concurrently.
 */
@Configuration
@EnableConfigurationProperties({UsageProperties.class, StructureSnapshotProperties.class})
public class CatalogDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogDataSourceConfig.class);

    private static final String USAGE_SCHEMA_RESOURCE = "usage-catalog-schema.sql";
    private static final String STRUCTURE_SCHEMA_RESOURCE = "structure-snapshot-schema.sql";

    @Bean
    public DataSource usageDataSource(UsageProperties properties, JdbcMcpProperties jdbcMcpProperties)
            throws IOException, SQLException {
        Path catalogDir = jdbcMcpProperties.catalogDir();
        Files.createDirectories(catalogDir);
        Path dbBase = jdbcMcpProperties.catalogDbBase();

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + dbBase.toAbsolutePath()
                + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;AUTO_SERVER=TRUE");

        initialiseSchema(ds, USAGE_SCHEMA_RESOURCE);
        initialiseSchema(ds, STRUCTURE_SCHEMA_RESOURCE);

        log.info("Active catalog: {} (dir: {})",
                jdbcMcpProperties.resolvedCatalogName(), catalogDir);
        log.info("Persistent catalog ready at {} (usage enabled={}, usage sources={})",
                jdbcMcpProperties.catalogDbFile().toAbsolutePath(),
                properties.catalogEnabled(), jdbcMcpProperties.usageCatalogDir());
        return ds;
    }

    private void initialiseSchema(DataSource ds, String resource) throws IOException, SQLException {
        String ddl = readSchema(resource);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(ddl)) {
                if (statement.isBlank()) continue;
                stmt.execute(statement);
            }
        }
    }

    private static String readSchema(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
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
