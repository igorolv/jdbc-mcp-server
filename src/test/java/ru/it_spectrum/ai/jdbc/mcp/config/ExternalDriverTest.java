package ru.it_spectrum.ai.jdbc.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Loads the sqlite-jdbc jar from the test class path as if it were a driver the user brought. */
class ExternalDriverTest {

    @TempDir
    Path dir;

    private Path sqliteJar() throws Exception {
        Path jar = Path.of(org.sqlite.JDBC.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Files.copy(jar, dir.resolve("sqlite-jdbc.jar"));
    }

    @Test
    void findsTheDriverThatAcceptsTheUrl() throws Exception {
        sqliteJar();
        try (ExternalDriver driver = ExternalDriver.load(dir.toString(), null, "jdbc:sqlite::memory:")) {
            assertThat(driver.driver().getClass().getName()).isEqualTo("org.sqlite.JDBC");
            assertThat(driver.driver().getClass()).isNotSameAs(org.sqlite.JDBC.class);
        }
    }

    @Test
    void loadsANamedDriverClassFromAJar() throws Exception {
        Path jar = sqliteJar();
        try (ExternalDriver driver = ExternalDriver.load(jar.toString(), "org.sqlite.JDBC", "jdbc:sqlite::memory:")) {
            assertThat(driver.driver().acceptsURL("jdbc:sqlite::memory:")).isTrue();
        }
    }

    @Test
    void explainsWhenNoDriverAcceptsTheUrl() throws Exception {
        sqliteJar();
        assertThatThrownBy(() -> ExternalDriver.load(dir.toString(), null, "jdbc:h2:mem:x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No driver in")
                .hasMessageContaining("org.sqlite.JDBC");
    }

    @Test
    void rejectsADirectoryWithoutJars() {
        assertThatThrownBy(() -> ExternalDriver.load(dir.toString(), null, "jdbc:sqlite::memory:"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contains no .jar files");
    }

    @Test
    void resolvesKindsExplicitlyOrFromTheUrl() {
        assertThat(DatabaseKind.resolve("jdbc:postgresql://h/db", null, false)).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(DatabaseKind.resolve("jdbc:postgresql://h/db", "generic", false)).isEqualTo(DatabaseKind.GENERIC);
        assertThat(DatabaseKind.resolve("jdbc:h2:mem:x", null, true)).isEqualTo(DatabaseKind.GENERIC);
        assertThat(DatabaseKind.resolve("jdbc:h2:mem:x", "FIREBIRD", true)).isEqualTo(DatabaseKind.FIREBIRD);
        assertThatThrownBy(() -> DatabaseKind.resolve("jdbc:h2:mem:x", null, false))
                .hasMessageContaining("driverPath");
        assertThatThrownBy(() -> DatabaseKind.resolve("jdbc:h2:mem:x", "mysql", true))
                .hasMessageContaining("Unknown dialect");
    }
}
