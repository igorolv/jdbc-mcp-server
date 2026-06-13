package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CatalogDataSourceConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void configuresWalAndSupportsTwoServerPoolsOnOneCatalog() throws Exception {
        JdbcMcpProperties jdbcMcp = new JdbcMcpProperties(tempDir.toString(), "shared");
        CatalogDataSourceConfig config = new CatalogDataSourceConfig();

        try (HikariDataSource first = config.usageDataSource(usageProperties(), jdbcMcp);
             HikariDataSource second = config.usageDataSource(usageProperties(), jdbcMcp)) {
            try (Connection conn = first.getConnection();
                 var statement = conn.createStatement()) {
                assertThat(queryString(statement, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
                assertThat(queryInt(statement, "PRAGMA foreign_keys")).isEqualTo(1);
                assertThat(queryInt(statement, "PRAGMA busy_timeout")).isEqualTo(10_000);
                assertThat(queryString(statement, "SELECT sqlite_version()"))
                        .isEqualTo("3.51.3");
                statement.executeUpdate("CREATE TABLE concurrent_test(id INTEGER PRIMARY KEY, value TEXT)");
                statement.executeUpdate("INSERT INTO concurrent_test(value) VALUES ('visible')");
            }

            try (Connection conn = second.getConnection();
                 var statement = conn.createStatement()) {
                assertThat(queryInt(statement, "SELECT COUNT(*) FROM concurrent_test")).isEqualTo(1);
            }

            new CatalogStorageService(first).checkpointForDistribution();
            Path walFile = Path.of(jdbcMcp.catalogDbFile() + "-wal");
            assertThat(Files.notExists(walFile) || Files.size(walFile) == 0).isTrue();
        }
    }

    @Test
    void competingWriterWaitsForTheActiveTransaction() throws Exception {
        JdbcMcpProperties jdbcMcp = new JdbcMcpProperties(tempDir.toString(), "writers");
        CatalogDataSourceConfig config = new CatalogDataSourceConfig();

        try (HikariDataSource first = config.usageDataSource(usageProperties(), jdbcMcp);
             HikariDataSource second = config.usageDataSource(usageProperties(), jdbcMcp);
             Connection writer = first.getConnection()) {
            try (var statement = writer.createStatement()) {
                statement.executeUpdate("CREATE TABLE writer_test(id INTEGER PRIMARY KEY, value TEXT)");
            }
            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                statement.executeUpdate("INSERT INTO writer_test(value) VALUES ('first')");
            }

            try (var executor = Executors.newSingleThreadExecutor()) {
                var waitingWriter = executor.submit(() -> {
                    try (Connection conn = second.getConnection();
                         var statement = conn.createStatement()) {
                        statement.executeUpdate("INSERT INTO writer_test(value) VALUES ('second')");
                    }
                    return null;
                });

                Thread.sleep(200);
                assertThat(waitingWriter).isNotDone();
                writer.commit();
                waitingWriter.get(5, TimeUnit.SECONDS);
            }

            try (Connection conn = second.getConnection();
                 var statement = conn.createStatement()) {
                assertThat(queryInt(statement, "SELECT COUNT(*) FROM writer_test")).isEqualTo(2);
            }
        }
    }

    @RepeatedTest(5)
    void twoServerPoolsCanInitializeTheSameCatalogConcurrently() throws Exception {
        JdbcMcpProperties jdbcMcp = new JdbcMcpProperties(tempDir.toString(), "startup-race");
        CatalogDataSourceConfig config = new CatalogDataSourceConfig();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> config.usageDataSource(usageProperties(), jdbcMcp));
            var second = executor.submit(() -> config.usageDataSource(usageProperties(), jdbcMcp));

            HikariDataSource firstDataSource = null;
            HikariDataSource secondDataSource = null;
            try {
                firstDataSource = first.get(15, TimeUnit.SECONDS);
                secondDataSource = second.get(15, TimeUnit.SECONDS);
                try (Connection conn = secondDataSource.getConnection();
                     var statement = conn.createStatement()) {
                    assertThat(queryString(statement, "PRAGMA journal_mode"))
                            .isEqualToIgnoringCase("wal");
                    assertThat(queryInt(statement,
                            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'"))
                            .isGreaterThan(0);
                }
            } finally {
                if (secondDataSource != null) secondDataSource.close();
                if (firstDataSource != null) firstDataSource.close();
            }
        }
    }

    @Test
    void leavesLegacyH2FileUntouched() throws Exception {
        JdbcMcpProperties jdbcMcp = new JdbcMcpProperties(tempDir.toString(), "legacy");
        Files.createDirectories(jdbcMcp.catalogDir());
        Files.writeString(jdbcMcp.legacyH2CatalogDbFile(), "legacy");

        try (HikariDataSource ignored =
                     new CatalogDataSourceConfig().usageDataSource(usageProperties(), jdbcMcp)) {
            assertThat(jdbcMcp.catalogDbFile()).exists();
            assertThat(jdbcMcp.legacyH2CatalogDbFile()).hasContent("legacy");
        }
    }

    @Test
    void separateJvmProcessesShareTheWalCatalog() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            JdbcMcpProperties jdbcMcp = new JdbcMcpProperties(tempDir.toString(), "processes");
            try (HikariDataSource dataSource =
                         new CatalogDataSourceConfig().usageDataSource(usageProperties(), jdbcMcp);
                 Connection conn = dataSource.getConnection();
                 var statement = conn.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE process_test(id INTEGER PRIMARY KEY, value TEXT)");
            }

            Process holder = startProbe(jdbcMcp.catalogDbFile(), "hold");
            try {
                assertThat(holder.inputReader().readLine()).isEqualTo("READY");
                Process writer = startProbe(jdbcMcp.catalogDbFile(), "write");
                try {
                    assertThat(writer.waitFor(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(writer.exitValue())
                            .withFailMessage(() -> writer.errorReader().lines()
                                    .collect(java.util.stream.Collectors.joining("\n")))
                            .isZero();
                } finally {
                    writer.destroyForcibly();
                }
                assertThat(holder.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(holder.exitValue()).isZero();
            } finally {
                holder.destroyForcibly();
            }

            try (HikariDataSource dataSource =
                         new CatalogDataSourceConfig().usageDataSource(usageProperties(), jdbcMcp);
                 Connection conn = dataSource.getConnection();
                 var statement = conn.createStatement()) {
                assertThat(queryInt(statement, "SELECT COUNT(*) FROM process_test")).isEqualTo(2);
            }
        });
    }

    private static UsageProperties usageProperties() {
        return new UsageProperties(true, List.of(), List.of(), true, true, true, 1_000);
    }

    private static Process startProbe(Path dbFile, String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                CatalogProcessProbe.class.getName(),
                dbFile.toString(),
                mode)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();
    }

    private static int queryInt(java.sql.Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static String queryString(java.sql.Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
