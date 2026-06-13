package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

/**
 * Subprocess used by {@link CatalogDataSourceConfigTest}.
 */
public final class CatalogProcessProbe {

    private CatalogProcessProbe() {
    }

    public static void main(String[] args) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(10_000);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + Path.of(args[0]).toAbsolutePath());

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            if ("hold".equals(args[1])) {
                connection.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO process_test(value) VALUES ('first')");
                System.out.println("READY");
                System.out.flush();
                Thread.sleep(1_000);
                connection.commit();
            } else {
                statement.executeUpdate("INSERT INTO process_test(value) VALUES ('second')");
            }
        }
    }
}
