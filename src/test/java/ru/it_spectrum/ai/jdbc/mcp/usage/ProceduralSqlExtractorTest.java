package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProceduralSqlExtractorTest {

    private final ProceduralSqlExtractor extractor = new ProceduralSqlExtractor();

    @Test
    void extractsReadAndWriteStatementsFromProceduralBody() {
        List<ExtractedSqlStatement> statements = extractor.extract("""
                CREATE PROCEDURE sync_customer AS
                BEGIN
                  SELECT c.id FROM customers c WHERE c.status = 'ACTIVE';
                  UPDATE customers SET touched_at = CURRENT_TIMESTAMP WHERE id = :id;
                  INSERT INTO audit_log(entity_id) SELECT c.id FROM customers c WHERE c.id = :id;
                END;
                """);

        assertThat(statements)
                .extracting(ExtractedSqlStatement::kind)
                .containsExactly("SELECT", "UPDATE", "INSERT");
        assertThat(statements)
                .extracting(ExtractedSqlStatement::sql)
                .containsExactly(
                        "SELECT c.id FROM customers c WHERE c.status = 'ACTIVE'",
                        "UPDATE customers SET touched_at = CURRENT_TIMESTAMP WHERE id = :id",
                        "INSERT INTO audit_log(entity_id) SELECT c.id FROM customers c WHERE c.id = :id");
    }

    @Test
    void ignoresKeywordsInsideStringsAndComments() {
        List<ExtractedSqlStatement> statements = extractor.extract("""
                BEGIN
                  -- SELECT ignored FROM comment;
                  v_sql := 'SELECT ignored FROM literal';
                  INSERT INTO audit_log(message) VALUES ('UPDATE ignored');
                END;
                """);

        assertThat(statements)
                .singleElement()
                .satisfies(statement -> {
                    assertThat(statement.kind()).isEqualTo("INSERT");
                    assertThat(statement.sql())
                            .isEqualTo("INSERT INTO audit_log(message) VALUES ('UPDATE ignored')");
                });
    }

    @Test
    void doesNotTreatNestedPredicateSelectAsSeparateStatement() {
        List<ExtractedSqlStatement> statements = extractor.extract("""
                BEGIN
                  IF EXISTS (SELECT 1 FROM customers c WHERE c.id = :id) THEN
                    DELETE FROM customer_queue WHERE customer_id = :id;
                  END IF;
                END;
                """);

        assertThat(statements)
                .singleElement()
                .satisfies(statement -> {
                    assertThat(statement.kind()).isEqualTo("DELETE");
                    assertThat(statement.sql())
                            .isEqualTo("DELETE FROM customer_queue WHERE customer_id = :id");
                });
    }

    @Test
    void extractsStatementsPerOraclePackageSubprogram() {
        List<ExtractedRoutineSqlStatement> statements = extractor.extractOraclePackageBody("""
                CREATE OR REPLACE PACKAGE BODY customer_pkg AS
                  FUNCTION active_customer_ids RETURN SYS_REFCURSOR IS
                    rc SYS_REFCURSOR;
                  BEGIN
                    OPEN rc FOR
                      SELECT c.id FROM customers c WHERE c.status = 'ACTIVE';
                    RETURN rc;
                  END active_customer_ids;

                  PROCEDURE touch_customer(p_id NUMBER) AS
                  BEGIN
                    IF p_id IS NOT NULL THEN
                      UPDATE customers SET touched_at = SYSDATE WHERE id = p_id;
                    END IF;
                  END;
                END customer_pkg;
                """);

        assertThat(statements)
                .extracting(ExtractedRoutineSqlStatement::routineName)
                .containsExactly("active_customer_ids", "touch_customer");
        assertThat(statements)
                .extracting(ExtractedRoutineSqlStatement::routineKind)
                .containsExactly("FUNCTION", "PROCEDURE");
        assertThat(statements)
                .extracting(ExtractedRoutineSqlStatement::sql)
                .containsExactly(
                        "SELECT c.id FROM customers c WHERE c.status = 'ACTIVE'",
                        "UPDATE customers SET touched_at = SYSDATE WHERE id = p_id");
    }
}
