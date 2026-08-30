package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
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
import java.util.List;

/**
 * Builds the persistent SQLite {@link DataSource} backing this catalog: it holds both the
 * usage-catalog index and the persistent structure snapshot in one {@code <catalog>.db} file under
 * {@code <data-dir>/<catalog>/}.
 *
 * <p>Both {@link UsageCatalogService} and {@code SqliteStructureSnapshotStore} consume this one
 * DataSource (bean name {@code usageDataSource}, kept for backwards-compatible wiring). On
 * initialisation both DDL scripts are applied idempotently ({@code CREATE TABLE IF NOT EXISTS}),
 * so opening an existing file is safe.
 *
 * <p>The structure snapshot is authoritative until explicitly invalidated or the file is deleted
 * ("cache forever"). WAL mode permits several local MCP server processes to read the same catalog
 * while SQLite serializes writes. A busy timeout makes competing writers wait instead of failing
 * immediately with {@code SQLITE_BUSY}.
 */
@Configuration
public class CatalogDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogDataSourceConfig.class);

    private static final String USAGE_SCHEMA_RESOURCE = "usage-catalog-schema.sql";
    private static final String STRUCTURE_SCHEMA_RESOURCE = "structure-snapshot-schema.sql";
    private static final long SQLITE_BOOTSTRAP_TIMEOUT_MILLIS = 10_000;

    @Bean(destroyMethod = "close")
    public HikariDataSource usageDataSource(
            UsageProperties properties, JdbcMcpProperties jdbcMcpProperties)
            throws IOException, SQLException {
        Path catalogDir = jdbcMcpProperties.catalogDir();
        Files.createDirectories(catalogDir);
        Path dbFile = jdbcMcpProperties.catalogDbFile().toAbsolutePath();
        Path legacyH2File = jdbcMcpProperties.legacyH2CatalogDbFile().toAbsolutePath();

        if (!Files.exists(dbFile) && Files.exists(legacyH2File)) {
            log.warn("Legacy H2 catalog detected at {}. It is not modified; creating a new SQLite "
                    + "catalog at {}. Run rebuildCatalog to populate it.", legacyH2File, dbFile);
        }

        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(10_000);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqlite);
        sqliteDataSource.setUrl("jdbc:sqlite:" + dbFile);
        enableWal(sqliteDataSource);

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqliteDataSource);
        hikari.setPoolName("jdbc-mcp-catalog-pool");
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(1);
        HikariDataSource dataSource = new HikariDataSource(hikari);

        try {
            initialiseSchema(dataSource, List.of(
                    USAGE_SCHEMA_RESOURCE, STRUCTURE_SCHEMA_RESOURCE));
            initialiseCatalogMeta(dataSource);
        } catch (IOException | SQLException | RuntimeException e) {
            dataSource.close();
            throw e;
        }

        log.info("Active catalog: {} (dir: {})",
                jdbcMcpProperties.resolvedCatalogName(), catalogDir);
        log.info("Persistent SQLite catalog ready at {} (WAL, usage enabled={}, usage sources={})",
                dbFile, properties.catalogEnabled(), jdbcMcpProperties.usageCatalogDir());
        return dataSource;
    }

    private void enableWal(DataSource dataSource) throws SQLException {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                        SQLITE_BOOTSTRAP_TIMEOUT_MILLIS);
        while (true) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 var rs = stmt.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!rs.next() || !"wal".equalsIgnoreCase(rs.getString(1))) {
                    throw new SQLException("Failed to enable SQLite WAL mode");
                }
                return;
            } catch (SQLException e) {
                if (!isBusy(e) || System.nanoTime() >= deadline) {
                    throw e;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while waiting to enable SQLite WAL mode",
                            interrupted);
                }
            }
        }
    }

    private static boolean isBusy(SQLException e) {
        return e.getErrorCode() == 5
                || (e.getMessage() != null && e.getMessage().contains("SQLITE_BUSY"));
    }

    private void initialiseSchema(DataSource ds, List<String> resources)
            throws IOException, SQLException {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String resource : resources) {
                String ddl = readSchema(resource);
                for (String statement : splitStatements(ddl)) {
                    if (statement.isBlank()) continue;
                    stmt.execute(statement);
                }
            }
        }
    }

    private void initialiseCatalogMeta(DataSource ds) throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO catalog_meta(meta_key, meta_value) "
                    + "VALUES ('storage_engine', 'sqlite') "
                    + "ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value");
            stmt.executeUpdate("INSERT INTO catalog_meta(meta_key, meta_value) "
                    + "VALUES ('format_version', '1') ON CONFLICT(meta_key) DO NOTHING");
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
