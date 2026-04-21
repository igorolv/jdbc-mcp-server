package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyGuardTest {

    private final ReadOnlyGuard guard = new ReadOnlyGuard(
            new JdbcProperties(null, null, null, null, 30, 1000, 500, "strict"));

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "select id from users",
            "  \n  SELECT * FROM t",
            "WITH cte AS (SELECT 1) SELECT * FROM cte",
            "EXPLAIN SELECT 1",
            "-- comment\nSELECT 1",
            "/* block */ SELECT 1",
            "SELECT 1;",
            "SELECT 'a; b; c' AS s",          // semicolon inside string literal is fine
            "SELECT \"a; b; c\" AS s",         // semicolon inside double-quoted ident too
            "SELECT 'it''s still read only' AS s",
            "SELECT \"a\"\"b\" FROM t",
    })
    void acceptsReadOnlyStatements(String sql) {
        guard.check(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO t VALUES (1)",
            "UPDATE t SET x = 1",
            "DELETE FROM t",
            "DROP TABLE t",
            "TRUNCATE t",
            "MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN UPDATE SET x = 1",
            "CREATE TABLE t(x int)",
            "ALTER TABLE t ADD COLUMN y int",
            "GRANT SELECT ON t TO u",
            "REVOKE SELECT ON t FROM u",
            "CALL proc(1)",
            "BEGIN; SELECT 1; COMMIT;",
            "SELECT 1; DELETE FROM t",       // multi-statement
            "DELETE FROM t; SELECT 1",
            "WITH moved AS (INSERT INTO t VALUES (1) RETURNING id) SELECT * FROM moved",
            "SELECT * INTO temp_table FROM src",
            "SELECT * FROM users FOR UPDATE",
            "",
            "   ",
            "-- just comments",
            "/* block only */",
    })
    void rejectsWriteStatements(String sql) {
        assertThatThrownBy(() -> guard.check(sql))
                .isInstanceOf(SqlNotAllowedException.class);
    }

    @Test
    void canBeDisabled() {
        ReadOnlyGuard off = new ReadOnlyGuard(
                new JdbcProperties(null, null, null, null, 30, 1000, 500, "off"));
        off.check("DELETE FROM t");  // does not throw
    }

    @Test
    void stripsLineComments() {
        assertThat(ReadOnlyGuard.stripComments("SELECT 1 -- comment\nFROM t"))
                .contains("SELECT 1")
                .doesNotContain("comment");
    }

    @Test
    void stripsBlockComments() {
        assertThat(ReadOnlyGuard.stripComments("SELECT /* hidden; DELETE */ 1"))
                .doesNotContain("DELETE")
                .contains("SELECT");
    }

    @Test
    void preservesStringLiterals() {
        assertThat(ReadOnlyGuard.stripComments("SELECT '-- not a comment'"))
                .isEqualTo("SELECT '-- not a comment'");
    }

    @Test
    void detectsStatementSeparator() {
        assertThat(ReadOnlyGuard.containsStatementSeparator("SELECT 1; DELETE")).isTrue();
        assertThat(ReadOnlyGuard.containsStatementSeparator("SELECT ';' FROM t")).isFalse();
        assertThat(ReadOnlyGuard.containsStatementSeparator("SELECT 'it''s; fine' FROM t")).isFalse();
    }

    @Test
    void ignoresForbiddenKeywordsInsideStringsCommentsAndQuotedIdentifiers() {
        assertThat(ReadOnlyGuard.findForbiddenKeyword("""
                SELECT 'INSERT INTO t VALUES (1)' AS sql_text,
                       "UPDATE",
                       col
                FROM t
                /* DELETE FROM t */
                """)).isNull();
    }

    @Test
    void findsForbiddenKeywordInCteBody() {
        assertThat(ReadOnlyGuard.findForbiddenKeyword(
                "WITH moved AS (INSERT INTO t VALUES (1) RETURNING id) SELECT * FROM moved"))
                .isEqualTo("INSERT");
    }
}
