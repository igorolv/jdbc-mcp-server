package ru.it_spectrum.ai.jdbc.mcp.resource;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CatalogResourceConfigTest {

    @TempDir
    Path dataDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogResourceConfig.class, Dependencies.class);

    @Test
    void resourcesAreDisabledByDefault() throws IOException {
        runner.withBean(ConnectionRegistry.class, () -> registry("orders", true))
                .run(context -> {
                    assertThat(context).doesNotHaveBean("jdbcCatalogResourceServices");
                    assertThat(context).doesNotHaveBean("jdbcCatalogResources");
                    assertThat(context).doesNotHaveBean("jdbcCatalogResourceTemplates");
                });
    }

    @Test
    void enablingResourcesExposesEveryCatalogWithALocalSnapshot() throws IOException {
        runner.withPropertyValues("jdbc-mcp.resources.enabled=true")
                .withBean(ConnectionRegistry.class, () -> registry("orders", true))
                .run(context -> {
                    assertThat(context).hasBean("jdbcCatalogResources");
                    assertThat(context).hasBean("jdbcCatalogResourceTemplates");
                    @SuppressWarnings("unchecked")
                    List<SyncResourceSpecification> resources =
                            (List<SyncResourceSpecification>) context.getBean("jdbcCatalogResources");
                    assertThat(resources).hasSize(1);
                    assertThat(resources.getFirst().resource().uri())
                            .isEqualTo("jdbc-mcp://catalog/orders/manifest");
                });
    }

    @Test
    void connectionsWithoutALocalCatalogAreSkipped() throws IOException {
        runner.withPropertyValues("jdbc-mcp.resources.enabled=true")
                .withBean(ConnectionRegistry.class, () -> registry("orders", false))
                .run(context -> {
                    assertThat(context.getBean("jdbcCatalogResources", List.class)).isEmpty();
                    assertThat(context.getBean("jdbcCatalogResourceTemplates", List.class)).isEmpty();
                });
    }

    private ConnectionRegistry registry(String name, boolean withSnapshot) {
        JdbcMcpProperties catalog = new JdbcMcpProperties(dataDir.toString(), name);
        if (withSnapshot) {
            try {
                Files.createDirectories(catalog.catalogDir());
                Files.createFile(catalog.catalogDbFile());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        ConnectionDefinition definition = new ConnectionDefinition(
                name, null,
                new JdbcProperties("jdbc:postgresql://localhost:5432/" + name, "u", "p", "public",
                        30, 1000, 100, "strict", 40, 0, 10_000, 5_000, 60_000),
                catalog,
                new UsageProperties(false, List.of(), List.of(), false, false, false, 0),
                new StructureSnapshotProperties(List.of(), 300),
                DatabaseKind.POSTGRESQL, null);
        Map<Class<?>, Object> beans = new IdentityHashMap<>();
        beans.put(MetadataService.class, mock(MetadataService.class));
        beans.put(StructureSnapshotStore.class, mock(StructureSnapshotStore.class));
        return ConnectionRegistry.fixed(ConnectionContext.ofBeans(definition, beans));
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean ObjectMapper objectMapper() { return new JsonConfig().jdbcMcpObjectMapper(); }
    }
}
