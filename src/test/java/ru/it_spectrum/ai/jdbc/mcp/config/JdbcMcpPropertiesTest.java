package ru.it_spectrum.ai.jdbc.mcp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMcpPropertiesTest {

    private static final String DATA_DIR = "/srv/jdbc-mcp-data";

    @Test
    void defaultsToDefaultCatalogWhenBlank() {
        for (String blank : new String[]{null, "", "   "}) {
            JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR, blank);

            assertThat(props.resolvedCatalogName()).isEqualTo("default");
            assertThat(props.catalogDir())
                    .isEqualTo(props.resolvedDataDir().resolve("default"));
            assertThat(props.usageCatalogDir())
                    .isEqualTo(props.resolvedDataDir().resolve("default").resolve("usage-catalog"));
            assertThat(props.logsDir())
                    .isEqualTo(props.resolvedDataDir().resolve("default").resolve("logs"));
        }
    }

    @Test
    void compatConstructorUsesDefaultCatalog() {
        JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR);

        assertThat(props.catalogName()).isNull();
        assertThat(props.resolvedCatalogName()).isEqualTo("default");
        assertThat(props.usageCatalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("default").resolve("usage-catalog"));
    }

    @Test
    void namedCatalogRootsEverythingUnderItsOwnDir() {
        JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR, "ssv");

        assertThat(props.resolvedCatalogName()).isEqualTo("ssv");
        assertThat(props.catalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("ssv"));
        assertThat(props.usageCatalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("ssv").resolve("usage-catalog"));
        assertThat(props.logsDir())
                .isEqualTo(props.resolvedDataDir().resolve("ssv").resolve("logs"));
    }

    @Test
    void catalogNameIsTrimmed() {
        JdbcMcpProperties props = new JdbcMcpProperties(DATA_DIR, "  ssv  ");

        assertThat(props.resolvedCatalogName()).isEqualTo("ssv");
        assertThat(props.catalogDir())
                .isEqualTo(props.resolvedDataDir().resolve("ssv"));
    }
}
