package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.FirebirdDialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaResolverTest {

    /** A schemaless engine answers without touching the database, so no executor is needed. */
    private final SchemaResolver firebird = new SchemaResolver(
            new JdbcProperties("jdbc:firebirdsql://h//db.fdb", "SYSDBA", "x",
                    null, 30, 1000, 100, "strict", 4, 0, 10_000, 5_000, 60_000),
            null, new FirebirdDialect());

    @Test
    void schemalessEngineResolvesToTheLogicalSchema() throws Exception {
        assertThat(firebird.resolve(null)).isEqualTo("PUBLIC");
        assertThat(firebird.resolve(" ")).isEqualTo("PUBLIC");
        assertThat(firebird.resolve("public")).isEqualTo("PUBLIC");
    }

    @Test
    void schemalessEngineRejectsOtherNames() {
        assertThatThrownBy(() -> firebird.resolve("dbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Firebird has no schemas")
                .hasMessageContaining("'PUBLIC'");
    }
}
