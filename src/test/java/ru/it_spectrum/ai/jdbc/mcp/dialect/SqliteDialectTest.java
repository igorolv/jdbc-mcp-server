package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteDialectTest {

    private final SqliteDialect dialect = new SqliteDialect();

    @Test
    void detectedFromTheUrlAndServedByTheBundledDriver() {
        assertThat(DatabaseKind.fromUrl("jdbc:sqlite:/data/app.db")).isEqualTo(DatabaseKind.SQLITE);
        assertThat(DatabaseKind.resolve("jdbc:sqlite:/data/app.db", null, false)).isEqualTo(DatabaseKind.SQLITE);
        assertThat(SqlDialect.forKind(DatabaseKind.SQLITE)).isInstanceOf(SqliteDialect.class);
        assertThat(dialect.readOnlyPoolConnections()).isFalse();
    }

    @Test
    void opensTheFileReadOnlyUnlessTheUrlSaysOtherwise() {
        assertThat(dialect.applyUrlTweaks("jdbc:sqlite:/data/app.db")).isEqualTo("jdbc:sqlite:/data/app.db?open_mode=1");
        assertThat(dialect.applyUrlTweaks("jdbc:sqlite:/data/app.db?busy_timeout=5000"))
                .isEqualTo("jdbc:sqlite:/data/app.db?busy_timeout=5000&open_mode=1");
        assertThat(dialect.applyUrlTweaks("jdbc:sqlite:/data/app.db?open_mode=6"))
                .isEqualTo("jdbc:sqlite:/data/app.db?open_mode=6");
    }

    @Test
    void schemaMainIdentifiersAndLimits() {
        assertThat(dialect.logicalSchema()).isEqualTo("main");
        assertThat(dialect.supportsSchemas()).isFalse();
        assertThat(dialect.qualify("main", "order")).isEqualTo("\"order\"");
        assertThat(dialect.limitQuery("SELECT * FROM t;", 5)).isEqualTo("SELECT * FROM t\nLIMIT 5");
        assertThat(dialect.limitQuery("SELECT * FROM t LIMIT 2", 5)).isEqualTo("SELECT * FROM t LIMIT 2");
    }

    /** Callers bind parameters positionally, always including the schema. */
    @Test
    void catalogQueriesTakeTheParametersTheirCallersBind() {
        assertParams(2, dialect::viewDefinitionQuery);       // schema, name
        assertParams(1, dialect::schemaViewsQuery);          // schema
        assertParams(2, dialect::searchObjectsQuery);        // pattern, pattern
        assertParams(2, dialect::listSequencesQuery);        // schema, schema
        assertParams(2, dialect::tableStatsQuery);           // schema, table
        assertParams(3, dialect::indexStatsQuery);           // schema, table, table
        assertParams(2, dialect::tableConstraintsQuery);     // schema, table
        assertParams(1, dialect::schemaConstraintsQuery);    // schema
        assertParams(2, dialect::tableTriggersQuery);        // schema, table
        assertParams(1, dialect::schemaTriggersQuery);       // schema
        assertParams(3, dialect::triggerDefinitionQuery);    // schema, table, trigger
        assertThat(dialect.routineSourceQuery()).isNull();
        assertThat(dialect.listRoutinesQuery()).isNull();
    }

    @Test
    void rendersThePlanTreeLikeTheSqliteShell() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT UNIQUE)");
            statement.execute("CREATE TABLE o (id INTEGER PRIMARY KEY, t_id INTEGER, qty INTEGER)");
            String plan = dialect.driverPlan(connection,
                    "SELECT * FROM t WHERE name = ? AND id IN (SELECT t_id FROM o WHERE qty > ?);");
            assertThat(plan).startsWith("QUERY PLAN\n")
                    .containsPattern("\\|--SEARCH t USING (COVERING )?INDEX")
                    .containsPattern("`--LIST SUBQUERY \\d+\n   (\\||`)--SCAN o");
        }
    }

    @Test
    void readsTriggerTimingAndEventAcrossLineBreaks() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (id INTEGER)");
            statement.execute("CREATE TRIGGER trg AFTER UPDATE\n  ON t BEGIN SELECT 1; END");
            try (PreparedStatement query = connection.prepareStatement(dialect.tableTriggersQuery())) {
                query.setString(1, "main");
                query.setString(2, "t");
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("timing")).isEqualTo("AFTER");
                    assertThat(rows.getString("events")).isEqualTo("UPDATE");
                }
            }
        }
    }

    private static void assertParams(int expected, Supplier<String> query) {
        int count = 0;
        boolean inLiteral = false;
        for (char c : query.get().toCharArray()) {
            if (c == '\'') inLiteral = !inLiteral;
            else if (c == '?' && !inLiteral) count++;
        }
        assertThat(count).isEqualTo(expected);
    }
}
