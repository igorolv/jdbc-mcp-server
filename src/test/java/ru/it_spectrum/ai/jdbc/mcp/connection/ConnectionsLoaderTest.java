package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
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

    private JdbcMcpProperties server() {
        return new JdbcMcpProperties(dataDir.toString(), null,
                dataDir.resolve("connections.json").toString());
    }

    private List<ConnectionDefinition> load(String json) throws IOException {
        if (json != null) {
            Files.writeString(dataDir.resolve("connections.json"), json);
        }
        return ConnectionsLoader.load(server(), MAPPER, ENV);
    }

    @Test
    void readsConnectionsAndSubstitutesEnvironmentPlaceholders() throws IOException {
        List<ConnectionDefinition> loaded = load("""
                {
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

        assertThat(loaded).extracting(ConnectionDefinition::name)
                .containsExactly("ssj", "legacy");

        ConnectionDefinition ssj = loaded.getFirst();
        assertThat(ssj.jdbc().username()).isEqualTo("ssj");
        assertThat(ssj.jdbc().password()).isEqualTo("s3cret");
        assertThat(ssj.description()).isEqualTo("Depositor service");
        assertThat(ssj.kind()).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(ssj.structureSnapshot().resolvedSchemas()).containsExactly("public", "nsi");
        assertThat(loaded.get(1).kind()).isEqualTo(DatabaseKind.ORACLE);
    }

    @Test
    void perConnectionSettingsOverrideTheBuiltInDefaultsAndTheRestIsInherited() throws IOException {
        List<ConnectionDefinition> loaded = load("""
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

        ConnectionDefinition reports = loaded.getFirst();
        assertThat(reports.jdbc().maxRows()).isEqualTo(50);
        assertThat(reports.jdbc().guardEnabled()).isFalse();
        assertThat(reports.usage().catalogEnabled()).isFalse();
        // inherited from the built-in defaults
        assertThat(reports.jdbc().queryTimeoutSeconds()).isEqualTo(30);
        assertThat(reports.jdbc().fetchSize()).isEqualTo(500);
        assertThat(reports.structureSnapshot().oracleColumnQueryTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void eachConnectionGetsItsOwnLocalCatalogDirectory() throws IOException {
        List<ConnectionDefinition> loaded = load("""
                {
                  "connections": {
                    "a": {"url": "jdbc:postgresql://h/a"},
                    "b": {"url": "jdbc:postgresql://h/b"}
                  }
                }
                """);

        assertThat(loaded.getFirst().catalog().catalogDbFile())
                .isEqualTo(dataDir.resolve("a").resolve("a.db"));
        assertThat(loaded.get(1).catalog().catalogDbFile())
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
    void serviceAtStandNamesGetTheirOwnLocalCatalogDirectory() throws IOException {
        List<ConnectionDefinition> loaded = load("""
                {
                  "connections": {
                    "ssj@dev": {"url": "jdbc:postgresql://h/ssj"},
                    "ssj@tst": {"url": "jdbc:postgresql://h/ssj"}
                  }
                }
                """);

        assertThat(loaded.getFirst().name()).isEqualTo("ssj@dev");
        assertThat(loaded.getFirst().catalog().catalogDbFile())
                .isEqualTo(dataDir.resolve("ssj@dev").resolve("ssj@dev.db"));
        assertThat(loaded.get(1).catalog().catalogDbFile())
                .isEqualTo(dataDir.resolve("ssj@tst").resolve("ssj@tst.db"));
    }

    @Test
    void dotDotIsRejectedInsteadOfEscapingTheDataDirectory() {
        assertThatThrownBy(() -> load("""
                {"connections": {"..": {"url": "jdbc:postgresql://h/x"}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid connection name '..'");
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
    void connectionWithoutUrlIsRejected() {
        assertThatThrownBy(() -> load("""
                {"connections": {"a": {"username": "u"}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no 'url'");
    }

    @Test
    void unsupportedUrlIsRecordedOnTheConnectionInsteadOfFailingStartup() throws IOException {
        List<ConnectionDefinition> loaded = load("""
                {
                  "connections": {
                    "good": {"url": "jdbc:postgresql://h/good"},
                    "broken": {"url": "jdbc:mysql://h/broken"}
                  }
                }
                """);

        assertThat(loaded.getFirst().usable()).isTrue();
        ConnectionDefinition broken = loaded.get(1);
        assertThat(broken.usable()).isFalse();
        assertThat(broken.configError()).contains("jdbc:mysql://h/broken");
        assertThat(broken.kind()).isNull();
    }

    @Test
    void aMissingFileIsAClearStartupError() {
        assertThatThrownBy(() -> load(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No database connections configured")
                .hasMessageContaining("connections.json");
    }

    @Test
    void aFileWithoutConnectionsIsAClearStartupError() {
        assertThatThrownBy(() -> load("""
                {"connections": {}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No database connections configured")
                .hasMessageContaining("connections.json");
    }

    @Test
    void malformedJsonNamesTheFile() throws IOException {
        Files.writeString(dataDir.resolve("connections.json"), "{ not json");
        assertThatThrownBy(() -> ConnectionsLoader.load(server(), MAPPER, ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connections.json");
    }
}
