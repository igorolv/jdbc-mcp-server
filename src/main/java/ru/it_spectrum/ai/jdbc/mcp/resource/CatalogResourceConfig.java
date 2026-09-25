package ru.it_spectrum.ai.jdbc.mcp.resource;

import io.modelcontextprotocol.server.McpServerFeatures.SyncCompletionSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the catalog-qualified MCP resources for every usable configured connection.
 *
 * <p>The registered set is fixed — a manifest and two URI templates per connection — and depends on
 * nothing but the connections file, so it never goes stale: a catalog built later by
 * {@code rebuildCatalog} is picked up by the next read or completion without re-registering anything.
 * Registration touches no file and no database; the connection's object graph is resolved only when a
 * client reads a resource or asks for completions.
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
            String name = definition.name();
            services.add(new CatalogResourceService(
                    () -> connections.resolve(name).metadata(),
                    () -> connections.resolve(name).snapshotStore(),
                    definition::hasLocalSnapshot,
                    mapper, definition.catalog(), definition.kind()));
        }
        return List.copyOf(services);
    }

    @Bean("jdbcCatalogResources")
    List<SyncResourceSpecification> jdbcCatalogResources(
            @Qualifier("jdbcCatalogResourceServices") List<CatalogResourceService> services) {
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

    @Bean("jdbcCatalogResourceCompletions")
    List<SyncCompletionSpecification> jdbcCatalogResourceCompletions(
            @Qualifier("jdbcCatalogResourceServices") List<CatalogResourceService> services) {
        List<SyncCompletionSpecification> completions = new ArrayList<>();
        for (CatalogResourceService service : services) {
            completions.addAll(service.completions());
        }
        return List.copyOf(completions);
    }
}
