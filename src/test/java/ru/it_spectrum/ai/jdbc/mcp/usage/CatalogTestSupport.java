package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Test helper that builds an isolated temporary SQLite catalog with both the usage-catalog
 * and structure-snapshot schemas applied — the unit-test analogue of {@link CatalogDataSourceConfig}
 * (which is file-backed in production).
 */
public final class CatalogTestSupport {

    private CatalogTestSupport() {
    }

    public static DataSource temporaryCatalog() throws Exception {
        Path dbFile = Files.createTempFile("jdbc-mcp-catalog-", ".db");
        dbFile.toFile().deleteOnExit();

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(10_000);

        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        apply(ds, "usage-catalog-schema.sql");
        apply(ds, "structure-snapshot-schema.sql");
        return ds;
    }

    private static void apply(DataSource ds, String resource) throws Exception {
        String ddl;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(ddl)) {
                if (!statement.isBlank()) stmt.execute(statement);
            }
        }
    }

    private static String[] splitStatements(String ddl) {
        StringBuilder out = new StringBuilder(ddl.length());
        for (String line : ddl.split("\\R", -1)) {
            int idx = line.indexOf("--");
            out.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
        }
        return out.toString().split(";\\s*\\R*");
    }
}
