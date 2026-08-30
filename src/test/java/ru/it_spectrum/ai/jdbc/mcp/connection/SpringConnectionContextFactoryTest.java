package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.PostgresDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore;
import ru.it_spectrum.ai.jdbc.mcp.plan.PostgresPlanParser;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-connection child context must be buildable — and useful for everything local — without a
 * reachable database. An unreachable port is exactly the point here: nothing in this test connects.
 */
class SpringConnectionContextFactoryTest {

    /** Nothing listens here; any attempt to reach it fails fast. */
    private static final String UNREACHABLE_URL = "jdbc:postgresql://127.0.0.1:1/absent";

    @TempDir
    Path dataDir;

    private AnnotationConfigApplicationContext parent;
    private ConnectionContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (parent != null) {
            parent.close();
        }
    }

    private ConnectionContext create(String name) {
        parent = new AnnotationConfigApplicationContext(GlobalDefaults.class, JsonConfig.class,
                JsonResponses.class);
        context = new SpringConnectionContextFactory(parent).create(definition(name));
        return context;
    }

    private ConnectionDefinition definition(String name) {
        return new ConnectionDefinition(name, "test connection",
                new JdbcProperties(UNREACHABLE_URL, "u", "p", "public", 5, 17, 100, "strict",
                        4, 0, 250, 250, 60_000),
                new JdbcMcpProperties(dataDir.toString(), name),
                new UsageProperties(true, List.of(), List.of(), true, true, true, 1000),
                new StructureSnapshotProperties(List.of("public"), 300),
                DatabaseKind.POSTGRESQL, null);
    }

    @Test
    void buildingTheContextOpensNoPoolAndNoDatabaseConnection() {
        ConnectionContext connection = create("lazy");

        assertThat(connection.name()).isEqualTo("lazy");
        assertThat(connection.kind()).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(connection.poolInitialized()).isFalse();
    }

    @Test
    void theLocalCatalogCanBeOpenedWithoutBuildingTheJdbcPool() {
        ConnectionContext connection = create("local");

        StructureSnapshotStore store = connection.snapshotStore();

        assertThat(store).isNotNull();
        assertThat(Files.exists(dataDir.resolve("local").resolve("local.db"))).isTrue();
        assertThat(connection.poolInitialized())
                .as("reading the local catalog must not build the JDBC pool")
                .isFalse();
    }

    @Test
    void servicesAreWiredWithTheConnectionSettingsRatherThanTheGlobalDefaults() {
        ConnectionContext connection = create("wired");

        assertThat(connection.bean(JdbcProperties.class).maxRows()).isEqualTo(17);
        assertThat(connection.bean(JdbcMcpProperties.class).resolvedCatalogName()).isEqualTo("wired");
        assertThat(connection.bean(StructureSnapshotProperties.class).resolvedSchemas())
                .containsExactly("public");
        assertThat(connection.bean(UsageProperties.class).catalogEnabled()).isTrue();
        assertThat(connection.bean(DatabaseKind.class)).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(connection.dialect()).isInstanceOf(PostgresDialect.class);
        assertThat(connection.planParser()).isInstanceOf(PostgresPlanParser.class);
        assertThat(connection.metadata()).isNotNull();
        assertThat(connection.usageCatalog()).isNotNull();
        assertThat(connection.poolInitialized())
                .as("asking for a database-facing service builds its pool")
                .isTrue();
    }

    @Test
    void anUnreachableDatabaseFailsOnTheCallRatherThanOnStartup() {
        ConnectionContext connection = create("down");
        MetadataService metadata = connection.metadata();

        assertThatThrownBy(() -> metadata.listSchemas(false)).isInstanceOf(SQLException.class);
    }

    @Test
    void twoConnectionsGetSeparateCatalogsAndSeparateServiceGraphs() {
        parent = new AnnotationConfigApplicationContext(GlobalDefaults.class, JsonConfig.class,
                JsonResponses.class);
        SpringConnectionContextFactory factory = new SpringConnectionContextFactory(parent);
        try (ConnectionContext first = factory.create(definition("one"));
             ConnectionContext second = factory.create(definition("two"))) {
            UsageCatalogService firstUsage = first.usageCatalog();
            UsageCatalogService secondUsage = second.usageCatalog();

            assertThat(firstUsage).isNotSameAs(secondUsage);
            assertThat(first.metadata()).isNotSameAs(second.metadata());
            assertThat(Files.exists(dataDir.resolve("one").resolve("one.db"))).isTrue();
            assertThat(Files.exists(dataDir.resolve("two").resolve("two.db"))).isTrue();
        }
    }

    /**
     * Stands in for the root context: the same settings types are present as global defaults, so a
     * child that failed to prefer its own would silently talk to the wrong database.
     */
    @Configuration(proxyBeanMethods = false)
    static class GlobalDefaults {

        @Bean
        JdbcProperties globalJdbcProperties() {
            return new JdbcProperties("jdbc:postgresql://global/global", "global", "global", "global",
                    99, 9999, 999, "off", 99, 9, 99_000, 99_000, 99_000);
        }

        @Bean
        JdbcMcpProperties globalJdbcMcpProperties() {
            return new JdbcMcpProperties("build/global-data", "global");
        }

        @Bean
        UsageProperties globalUsageProperties() {
            return new UsageProperties(false, List.of(), List.of(), false, false, false, 1);
        }

        @Bean
        StructureSnapshotProperties globalStructureSnapshotProperties() {
            return new StructureSnapshotProperties(List.of("global"), 1);
        }
    }
}
