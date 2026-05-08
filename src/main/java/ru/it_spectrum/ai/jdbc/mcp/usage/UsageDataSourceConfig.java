package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

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
 * Builds the SQLite-backed {@link DataSource} for the usage catalog and initialises the schema.
 *
 * <p>The bean is published unconditionally to keep wiring simple; whether the catalog actually
 * accepts ingest requests is decided by {@link UsageProperties#catalogEnabled()} inside the
 * service layer.
 *
 * <p>SQLite is configured with foreign-key enforcement and WAL journal mode so that read-side
 * tools (which do not modify the catalog) can run concurrently with an ongoing ingest call.
 */
@Configuration
@EnableConfigurationProperties(UsageProperties.class)
public class UsageDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(UsageDataSourceConfig.class);

    private static final String SCHEMA_RESOURCE = "usage-catalog-schema.sql";

    @Bean
    public DataSource usageDataSource(UsageProperties properties) throws IOException, SQLException {
        Path file = properties.resolvedCatalogPath().toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);

        SQLiteDataSource ds = new SQLiteDataSource(sqliteConfig);
        ds.setUrl("jdbc:sqlite:" + file);

        initialiseSchema(ds);

        log.info("Usage catalog ready at {} (enabled={})", file, properties.catalogEnabled());
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
