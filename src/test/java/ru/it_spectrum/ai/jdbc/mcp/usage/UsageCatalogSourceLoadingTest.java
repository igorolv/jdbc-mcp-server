package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class UsageCatalogSourceLoadingTest {

    @TempDir
    Path tempDir;

    @Test
    void indexesJsonFilesFromDirectoriesAndZipArchives() throws Exception {
        Path dir = tempDir.resolve("usage");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("customers.json"), """
                {
                  "source": {"kind": "dao", "path": "CustomerDao.java", "unit": "findOne"},
                  "sql": "SELECT c.id, c.name FROM customers c WHERE c.id = :id"
                }
                """, StandardCharsets.UTF_8);

        Path zip = tempDir.resolve("reports.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("orders/order-summary.json"));
            out.write("""
                    {
                      "source": {"kind": "report", "path": "orders/summary.xdo"},
                      "sql": "SELECT o.id FROM orders o JOIN customers c ON o.customer_id = c.id"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        UsageCatalogService service = service(List.of(dir.toString(), zip.toString()));

        UsageCatalogStatus status = service.rebuildFromSources();

        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.filesScanned()).isEqualTo(2);
        assertThat(status.recordsLoaded()).isEqualTo(2);
        assertThat(service.findQueriesByTable(null, "customers").count()).isEqualTo(2);
        assertThat(service.observedRelationships(null, null, 1).relationships())
                .singleElement()
                .satisfies(edge -> assertThat(edge.support()).isEqualTo(1));
    }

    @Test
    void indexesJsonFilesContainingRecordArrays() throws Exception {
        Path dir = tempDir.resolve("usage");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("batch.json"), """
                [
                  {
                    "source": {"kind": "dao", "path": "CustomerDao.java", "unit": "findOne"},
                    "sql": "SELECT c.id, c.name FROM customers c WHERE c.id = :id"
                  },
                  {
                    "source": {"kind": "dao", "path": "OrderDao.java", "unit": "findByCustomer"},
                    "sql": "SELECT o.id FROM orders o WHERE o.customer_id = :customerId"
                  }
                ]
                """, StandardCharsets.UTF_8);

        UsageCatalogService service = service(List.of(dir.toString()));

        UsageCatalogStatus status = service.rebuildFromSources();

        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.filesScanned()).isEqualTo(1);
        assertThat(status.recordsLoaded()).isEqualTo(2);
        assertThat(status.invalidFiles()).isZero();
        assertThat(service.findQueriesByTable(null, "customers").count()).isEqualTo(1);
        assertThat(service.findQueriesByTable(null, "orders").count()).isEqualTo(1);
    }

    @Test
    void reportsDuplicateUidsAndKeepsFirstRecord() throws Exception {
        Path dir = tempDir.resolve("usage");
        Files.createDirectories(dir);
        String one = """
                {
                  "source": {"kind": "manual", "path": "same.sql"},
                  "sql": "SELECT id FROM customers"
                }
                """;
        String two = one.replace("customers", "orders");
        Files.writeString(dir.resolve("a.json"), one, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.json"), two, StandardCharsets.UTF_8);

        UsageCatalogService service = service(List.of(dir.toString()));

        UsageCatalogStatus status = service.rebuildFromSources();

        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.duplicateUids()).isEqualTo(1);
        assertThat(status.recordsLoaded()).isEqualTo(1);
        assertThat(service.findQueriesByTable(null, "customers").count()).isEqualTo(1);
        assertThat(service.findQueriesByTable(null, "orders").count()).isEqualTo(0);
    }

    @Test
    void mergesRecordsFromAdditionalCatalogSources() throws Exception {
        UsageCatalogSource source = new UsageCatalogSource() {
            @Override
            public String name() {
                return "database-native";
            }

            @Override
            public List<QueryUsage> load() {
                return List.of(new QueryUsage(
                        new QueryUsageSource("database-view", "native/view/public.customer_orders_v", null),
                        null,
                        null,
                        List.of("database-native"),
                        "SELECT c.id FROM customers c",
                        null,
                        null,
                        null,
                        null));
            }
        };
        UsageCatalogService service = service(List.of(), List.of(source));

        UsageCatalogStatus status = service.rebuildFromSources();

        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.sources()).contains("database-native");
        assertThat(status.recordsLoaded()).isEqualTo(1);
        assertThat(service.findQueriesByTable(null, "customers").count()).isEqualTo(1);
    }

    private UsageCatalogService service(List<String> paths) throws Exception {
        return service(paths, List.of());
    }

    private UsageCatalogService service(List<String> paths, List<UsageCatalogSource> sources) throws Exception {
        UsageProperties properties = properties(paths);
        JdbcMcpProperties jdbcMcpProperties = jdbcMcpProperties();
        DataSource ds = new UsageDataSourceConfig().usageDataSource(properties, jdbcMcpProperties);
        return new UsageCatalogService(properties, ds, new QueryAnalysisService(),
                new JsonResponses(new JsonConfig().jdbcMcpObjectMapper()), jdbcMcpProperties,
                new ObjectMapper(), sources, null);
    }

    private JdbcMcpProperties jdbcMcpProperties() {
        return new JdbcMcpProperties(tempDir.resolve("data").toString());
    }

    private static UsageProperties properties(List<String> paths) {
        return new UsageProperties(true, paths, List.of(), true, true, true, 1_000);
    }
}
