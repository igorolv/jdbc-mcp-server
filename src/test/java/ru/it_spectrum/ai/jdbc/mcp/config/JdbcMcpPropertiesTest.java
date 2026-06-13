package ru.it_spectrum.ai.jdbc.mcp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMcpPropertiesTest {

    private static final String DATA_DIR = "/srv/jdbc-mcp-data";

    @Test
    void legacyModeWhenCatalogNameBlank() {
        for (String blank : new String[]{null, "", "   "}) {
            JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR, blank);

            assertThat(props.isLegacyCatalog()).isTrue();
            assertThat(props.resolvedCatalogName()).isEqualTo("default");
            // Legacy layout must stay byte-identical to the pre-catalog server.
            assertThat(props.catalogDir()).isEqualTo(props.resolvedDataDir());
            assertThat(props.usageCatalogDir())
                    .isEqualTo(props.resolvedDataDir().resolve("usage-catalog"));
        }
    }

    @Test
    void compatConstructorIsLegacy() {
        JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR);

        assertThat(props.catalogName()).isNull();
        assertThat(props.isLegacyCatalog()).isTrue();
        assertThat(props.resolvedCatalogName()).isEqualTo("default");
        assertThat(props.usageCatalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("usage-catalog"));
    }

    @Test
    void namedCatalogUsesCatalogsSubdir() {
        JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR, "ssv");

        assertThat(props.isLegacyCatalog()).isFalse();
        assertThat(props.resolvedCatalogName()).isEqualTo("ssv");
        assertThat(props.catalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("catalogs").resolve("ssv"));
        assertThat(props.usageCatalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("catalogs").resolve("ssv").resolve("usage-catalog"));
    }

    @Test
    void catalogNameIsTrimmed() {
        JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR, "  ssv  ");

        assertThat(props.isLegacyCatalog()).isFalse();
        assertThat(props.resolvedCatalogName()).isEqualTo("ssv");
        assertThat(props.catalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("catalogs").resolve("ssv"));
    }

    @Test
    void explicitDefaultNameIsNamedNotLegacy() {
        // Explicitly setting JDBC_MCP_CATALOG=default opts into the new layout, not legacy.
        JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR, "default");

        assertThat(props.isLegacyCatalog()).isFalse();
        assertThat(props.resolvedCatalogName()).isEqualTo("default");
        assertThat(props.usageCatalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("catalogs").resolve("default").resolve("usage-catalog"));
    }
}
