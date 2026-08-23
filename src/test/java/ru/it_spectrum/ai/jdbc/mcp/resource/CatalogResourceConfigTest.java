package ru.it_spectrum.ai.jdbc.mcp.resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CatalogResourceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogResourceConfig.class, Dependencies.class);

    @Test
    void resourcesAreDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CatalogResourceService.class);
            assertThat(context).doesNotHaveBean("jdbcCatalogResources");
            assertThat(context).doesNotHaveBean("jdbcCatalogResourceTemplates");
        });
    }

    @Test
    void resourcesCanBeEnabledWithoutChangingToolConfiguration() {
        runner.withPropertyValues("jdbc-mcp.resources.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(CatalogResourceService.class);
                    assertThat(context).hasBean("jdbcCatalogResources");
                    assertThat(context).hasBean("jdbcCatalogResourceTemplates");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean MetadataService metadataService() { return mock(MetadataService.class); }
        @Bean StructureSnapshotStore structureSnapshotStore() { return mock(StructureSnapshotStore.class); }
        @Bean ObjectMapper objectMapper() { return new JsonConfig().jdbcMcpObjectMapper(); }
        @Bean JdbcMcpProperties jdbcMcpProperties() { return new JdbcMcpProperties("build/test", "orders"); }
        @Bean DatabaseKind databaseKind() { return DatabaseKind.POSTGRESQL; }
    }
}
