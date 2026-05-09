package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.IndexerStatusResponse;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class UsageCatalogIndexerTest {

    @TempDir
    Path tempDir;

    @Test
    void indexesJsonFilesFromDirectoriesAndZipArchives() throws Exception {
        Path dir = tempDir.resolve("usage");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("customers.json"), """
                {
                  "dataSource": "SHOP",
                  "source": {"kind": "dao", "path": "CustomerDao.java", "unit": "findOne"},
                  "sql": "SELECT c.id, c.name FROM customers c WHERE c.id = :id"
                }
                """, StandardCharsets.UTF_8);

        Path zip = tempDir.resolve("reports.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("orders/order-summary.json"));
            out.write("""
                    {
                      "dataSource": "SHOP",
                      "source": {"kind": "report", "path": "orders/summary.xdo"},
                      "sql": "SELECT o.id FROM orders o JOIN customers c ON o.customer_id = c.id"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        UsageCatalogService service = service(List.of(dir.toString(), zip.toString()));
        UsageCatalogIndexer indexer = indexer(service, List.of(dir.toString(), zip.toString()));

        IndexerStatusResponse status = indexer.refreshBlocking();

        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.filesScanned()).isEqualTo(2);
        assertThat(status.recordsLoaded()).isEqualTo(2);
        assertThat(service.findQueriesByTable(null, "customers").count()).isEqualTo(2);
        assertThat(service.observedRelationships(null, null, 1).relationships())
                .singleElement()
                .satisfies(edge -> assertThat(edge.support()).isEqualTo(1));
    }

    @Test
    void reportsDuplicateUidsAndKeepsFirstRecord() throws Exception {
        Path dir = tempDir.resolve("usage");
        Files.createDirectories(dir);
        String one = """
                {
                  "dataSource": "SHOP",
                  "source": {"kind": "manual", "path": "same.sql"},
                  "sql": "SELECT id FROM customers"
                }
                """;
        String two = one.replace("customers", "orders");
        Files.writeString(dir.resolve("a.json"), one, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.json"), two, StandardCharsets.UTF_8);

        UsageCatalogService service = service(List.of(dir.toString()));
        UsageCatalogIndexer indexer = indexer(service, List.of(dir.toString()));

        IndexerStatusResponse status = indexer.refreshBlocking();

        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.duplicateUids()).isEqualTo(1);
        assertThat(status.recordsLoaded()).isEqualTo(1);
        assertThat(service.findQueriesByTable(null, "customers").count()).isEqualTo(1);
        assertThat(service.findQueriesByTable(null, "orders").count()).isEqualTo(0);
    }

    @Test
    void mergesRecordsFromAdditionalCatalogSources() throws Exception {
        UsageCatalogService service = service(List.of());
        UsageCatalogSource source = new UsageCatalogSource() {
            @Override
            public String name() {
                return "database-native";
            }

            @Override
            public List<QueryUsage> load() {
                return List.of(new QueryUsage(
                        "SHOP",
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
        UsageCatalogIndexer indexer = new UsageCatalogIndexer(
                properties(List.of()), new JdbcMcpProperties(""), service, new ObjectMapper(), List.of(source));

        IndexerStatusResponse status = indexer.refreshBlocking();

        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.sources()).contains("database-native");
        assertThat(status.recordsLoaded()).isEqualTo(1);
        assertThat(service.findQueriesByTable(null, "customers").count()).isEqualTo(1);
    }

    private UsageCatalogService service(List<String> paths) throws Exception {
        UsageProperties properties = properties(paths);
        JdbcMcpProperties jdbcMcpProperties = new JdbcMcpProperties("");
        DataSource ds = new UsageDataSourceConfig().usageDataSource(properties, jdbcMcpProperties);
        return new UsageCatalogService(properties, ds, new QueryAnalysisService(),
                new JsonResponses(new JsonConfig().jdbcMcpObjectMapper()));
    }

    private UsageCatalogIndexer indexer(UsageCatalogService service, List<String> paths) {
        return new UsageCatalogIndexer(properties(paths), new JdbcMcpProperties(""), service, new ObjectMapper());
    }

    private static UsageProperties properties(List<String> paths) {
        return new UsageProperties(true, paths, false, false, false, "");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Map<String, Object> root, String key) {
        return (List<Map<String, Object>>) root.get(key);
    }
}
