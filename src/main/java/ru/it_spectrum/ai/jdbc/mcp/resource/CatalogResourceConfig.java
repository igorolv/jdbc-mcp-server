package ru.it_spectrum.ai.jdbc.mcp.resource;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.sql.SQLException;

/** Registers the catalog-qualified MCP resources exposed by this server instance. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jdbc-mcp.resources", name = "enabled",
        havingValue = "true")
public class CatalogResourceConfig {

    @Bean
    CatalogResourceService catalogResourceService(MetadataService metadata,
                                                   StructureSnapshotStore snapshotStore,
                                                   ObjectMapper mapper,
                                                   JdbcMcpProperties jdbcMcpProperties,
                                                   DatabaseKind databaseKind) {
        return new CatalogResourceService(metadata, snapshotStore, mapper, jdbcMcpProperties, databaseKind);
    }

    @Bean("jdbcCatalogResources")
    List<SyncResourceSpecification> jdbcCatalogResources(CatalogResourceService resources) throws SQLException {
        return resources.resources();
    }

    @Bean("jdbcCatalogResourceTemplates")
    List<SyncResourceTemplateSpecification> jdbcCatalogResourceTemplates(CatalogResourceService resources) {
        return resources.resourceTemplates();
    }
}
