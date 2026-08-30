package ru.it_spectrum.ai.jdbc.mcp.resource;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.sql.SQLException;

/**
 * Registers the catalog-qualified MCP resources for every configured connection that already has a
 * local catalog file.
 *
 * <p>Building the list touches local SQLite files only — no database is contacted and no JDBC pool
 * is created, so enabling resources stays cheap even with a dozen connections configured. Each
 * catalog gets its own manifest, concrete table resources and URI templates; since every URI is
 * qualified by catalog name, a read routes itself back to the connection it came from.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jdbc-mcp.resources", name = "enabled",
        havingValue = "true")
public class CatalogResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogResourceConfig.class);

    @Bean("jdbcCatalogResourceServices")
    List<CatalogResourceService> catalogResourceServices(ConnectionRegistry connections,
                                                         ObjectMapper mapper) {
        List<CatalogResourceService> services = new ArrayList<>();
        for (ConnectionDefinition definition : connections.definitions()) {
            if (!definition.usable()) {
                log.warn("Skipping resources for connection '{}': {}", definition.name(),
                        definition.configError());
                continue;
            }
            if (!definition.hasLocalSnapshot()) {
                log.info("Skipping resources for connection '{}': no local catalog at {}",
                        definition.name(), definition.catalog().catalogDbFile());
                continue;
            }
            try {
                ConnectionContext context = connections.resolve(definition.name());
                services.add(new CatalogResourceService(context::metadata, context.snapshotStore(),
                        mapper, definition.catalog(), definition.kind()));
            } catch (RuntimeException e) {
                log.warn("Skipping resources for connection '{}': {}", definition.name(), e.getMessage());
            }
        }
        return List.copyOf(services);
    }

    @Bean("jdbcCatalogResources")
    List<SyncResourceSpecification> jdbcCatalogResources(
            @Qualifier("jdbcCatalogResourceServices") List<CatalogResourceService> services)
            throws SQLException {
        List<SyncResourceSpecification> resources = new ArrayList<>();
        for (CatalogResourceService service : services) {
            resources.addAll(service.resources());
        }
        return List.copyOf(resources);
    }

    @Bean("jdbcCatalogResourceTemplates")
    List<SyncResourceTemplateSpecification> jdbcCatalogResourceTemplates(
            @Qualifier("jdbcCatalogResourceServices") List<CatalogResourceService> services) {
        List<SyncResourceTemplateSpecification> templates = new ArrayList<>();
        for (CatalogResourceService service : services) {
            templates.addAll(service.resourceTemplates());
        }
        return List.copyOf(templates);
    }
}
