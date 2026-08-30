package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionsLoaderTest {

    private static final ObjectMapper MAPPER = new JsonConfig().jdbcMcpObjectMapper();

    private static final UnaryOperator<String> ENV =
            Map.of("SSJ_PASSWORD", "s3cret", "SSJ_USER", "ssj")::get;

    @TempDir
    Path dataDir;

    private JdbcProperties globalJdbc(String url) {
        return new JdbcProperties(url, "envuser", "envpass", "", 30, 1000, 500, "strict",
                40, 0, 10_000, 5_000, 60_000);
    }

    private JdbcMcpProperties globalCatalog() {
        return new JdbcMcpProperties(dataDir.toString(), "default");
    }

    private UsageProperties globalUsage() {
        return new UsageProperties(true, List.of(), List.of(), true, true, true, 10_000);
    }

    private StructureSnapshotProperties globalSnapshot() {
        return new StructureSnapshotProperties(List.of(), 300);
    }

    private ConnectionsLoader.Loaded load(String json) throws IOException {
        return load(json, globalJdbc(""));
    }

    private ConnectionsLoader.Loaded load(String json, JdbcProperties globalJdbc) throws IOException {
        Path file = dataDir.resolve("connections.json");
        if (json != null) {
            Files.writeString(file, json);
        }
        return ConnectionsLoader.load(file, globalJdbc, globalCatalog(), globalUsage(),
                globalSnapshot(), MAPPER, ENV);
    }

    @Test
    void readsConnectionsAndSubstitutesEnvironmentPlaceholders() throws IOException {
        ConnectionsLoader.Loaded loaded = load("""
                {
                  "defaultConnection": "ssj",
                  "connections": {
                    "ssj": {
                      "url": "jdbc:postgresql://db.example.com/ssj",
                      "username": "${SSJ_USER}",
                      "password": "${SSJ_PASSWORD}",
                      "defaultSchema": "public",
                      "description": "Depositor service",
                      "structureSnapshotSchemas": ["public", "nsi"]
                    },
                    "legacy": {
                      "url": "jdbc:oracle:thin:@//oracle.example.com:1521/LEGACY",
                      "username": "app",
                      "password": "plain"
                    }
                  }
                }
                """);

        assertThat(loaded.defaultConnection()).isEqualTo("ssj");
        assertThat(loaded.definitions()).extracting(ConnectionDefinition::name)
                .containsExactly("ssj", "legacy");

        ConnectionDefinition ssj = loaded.definitions().getFirst();
        assertThat(ssj.jdbc().username()).isEqualTo("ssj");
        assertThat(ssj.jdbc().password()).isEqualTo("s3cret");
        assertThat(ssj.description()).isEqualTo("Depositor service");
        assertThat(ssj.kind()).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(ssj.structureSnapshot().resolvedSchemas()).containsExactly("public", "nsi");
        assertThat(loaded.definitions().get(1).kind()).isEqualTo(DatabaseKind.ORACLE);
    }

    @Test
    void perConnectionSettingsOverrideGlobalDefaultsAndTheRestIsInherited() throws IOException {
        ConnectionsLoader.Loaded loaded = load("""
                {
                  "connections": {
                    "reports": {
                      "url": "jdbc:postgresql://db.example.com/reports",
                      "maxRows": 50,
                      "readonlyGuard": "off",
                      "usageCatalogEnabled": false
                    }
                  }
                }
                """);

        ConnectionDefinition reports = loaded.definitions().getFirst();
        assertThat(reports.jdbc().maxRows()).isEqualTo(50);
        assertThat(reports.jdbc().guardEnabled()).isFalse();
        assertThat(reports.usage().catalogEnabled()).isFalse();
        // inherited from the environment defaults
        assertThat(reports.jdbc().queryTimeoutSeconds()).isEqualTo(30);
        assertThat(reports.jdbc().fetchSize()).isEqualTo(500);
        assertThat(reports.structureSnapshot().oracleColumnQueryTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void eachConnectionGetsItsOwnLocalCatalogDirectory() throws IOException {
        ConnectionsLoader.Loaded loaded = load("""
                {
                  "connections": {
                    "a": {"url": "jdbc:postgresql://h/a"},
                    "b": {"url": "jdbc:postgresql://h/b"}
                  }
                }
                """);

        assertThat(loaded.definitions().getFirst().catalog().catalogDbFile())
                .isEqualTo(dataDir.resolve("a").resolve("a.db"));
        assertThat(loaded.definitions().get(1).catalog().catalogDbFile())
                .isEqualTo(dataDir.resolve("b").resolve("b.db"));
    }

    @Test
    void missingEnvironmentVariableFailsWithTheVariableName() {
        assertThatThrownBy(() -> load("""
                {"connections": {"ssj": {"url": "jdbc:postgresql://h/ssj", "password": "${NO_SUCH_VAR}"}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO_SUCH_VAR")
                .hasMessageContaining("connections.ssj.password");
    }

    @Test
    void invalidConnectionNameIsRejected() {
        assertThatThrownBy(() -> load("""
                {"connections": {"bad/name": {"url": "jdbc:postgresql://h/x"}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad/name");
    }

    @Test
    void unknownDefaultConnectionIsRejected() {
        assertThatThrownBy(() -> load("""
                {"defaultConnection": "nope", "connections": {"a": {"url": "jdbc:postgresql://h/a"}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("a");
    }

    @Test
    void connectionWithoutUrlIsRejected() {
        assertThatThrownBy(() -> load("""
                {"connections": {"a": {"username": "u"}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no 'url'");
    }

    @Test
    void unsupportedUrlIsRecordedOnTheConnectionInsteadOfFailingStartup() throws IOException {
        ConnectionsLoader.Loaded loaded = load("""
                {
                  "connections": {
                    "good": {"url": "jdbc:postgresql://h/good"},
                    "broken": {"url": "jdbc:mysql://h/broken"}
                  }
                }
                """);

        assertThat(loaded.definitions().getFirst().usable()).isTrue();
        ConnectionDefinition broken = loaded.definitions().get(1);
        assertThat(broken.usable()).isFalse();
        assertThat(broken.configError()).contains("jdbc:mysql://h/broken");
        assertThat(broken.kind()).isNull();
    }

    @Test
    void environmentOnlySetupKeepsWorkingWithoutAConnectionsFile() throws IOException {
        ConnectionsLoader.Loaded loaded = ConnectionsLoader.load(
                dataDir.resolve("connections.json"),
                globalJdbc("jdbc:postgresql://db.example.com/app"),
                new JdbcMcpProperties(dataDir.toString(), "app"),
                globalUsage(), globalSnapshot(), MAPPER, ENV);

        assertThat(loaded.definitions()).hasSize(1);
        ConnectionDefinition app = loaded.definitions().getFirst();
        assertThat(app.name()).isEqualTo("app");
        assertThat(app.jdbc().username()).isEqualTo("envuser");
        assertThat(app.catalog().catalogDbFile()).isEqualTo(dataDir.resolve("app").resolve("app.db"));
        assertThat(loaded.defaultConnection()).isNull();
    }

    @Test
    void environmentConnectionIsAddedAlongsideTheFileEntries() throws IOException {
        ConnectionsLoader.Loaded loaded = load("""
                {"connections": {"ssj": {"url": "jdbc:postgresql://h/ssj"}}}
                """, globalJdbc("jdbc:postgresql://h/env"));

        assertThat(loaded.definitions()).extracting(ConnectionDefinition::name)
                .containsExactly("ssj", "default");
    }

    @Test
    void clashingEnvironmentAndFileConnectionNamesFailStartup() {
        assertThatThrownBy(() -> load("""
                {"connections": {"default": {"url": "jdbc:postgresql://h/ssj"}}}
                """, globalJdbc("jdbc:postgresql://h/env")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'default'")
                .hasMessageContaining("JDBC_MCP_CATALOG");
    }

    @Test
    void noFileAndNoUrlIsAClearStartupError() {
        assertThatThrownBy(() -> load(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_URL")
                .hasMessageContaining("connections.json");
    }

    @Test
    void malformedJsonNamesTheFile() throws IOException {
        Path file = dataDir.resolve("connections.json");
        Files.writeString(file, "{ not json");
        assertThatThrownBy(() -> ConnectionsLoader.load(file, globalJdbc(""), globalCatalog(),
                globalUsage(), globalSnapshot(), MAPPER, ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connections.json");
    }
}
