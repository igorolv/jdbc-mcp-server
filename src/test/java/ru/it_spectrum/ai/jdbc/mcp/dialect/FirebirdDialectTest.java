package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirebirdDialectTest {

    private final FirebirdDialect dialect = new FirebirdDialect();

    @Test
    void detectedFromBothJaybirdUrlPrefixes() {
        assertThat(DatabaseKind.fromUrl("jdbc:firebirdsql://host:3050//data/app.fdb")).isEqualTo(DatabaseKind.FIREBIRD);
        assertThat(DatabaseKind.fromUrl("jdbc:firebird://host/app")).isEqualTo(DatabaseKind.FIREBIRD);
        assertThat(DatabaseKind.fromUrl("jdbc:firebirdsql:embedded:C:/data/app.gdb")).isEqualTo(DatabaseKind.FIREBIRD);
        assertThat(SqlDialect.forKind(DatabaseKind.FIREBIRD)).isInstanceOf(FirebirdDialect.class);
    }

    @Test
    void presentsOneLogicalSchemaThatNeverReachesSql() {
        assertThat(dialect.supportsSchemas()).isFalse();
        assertThat(dialect.logicalSchema()).isEqualTo("PUBLIC");
        assertThat(dialect.fallbackSchema(null)).isEqualTo("PUBLIC");
        assertThat(dialect.systemSchemas()).isEmpty();
        assertThat(dialect.qualify("PUBLIC", "FIAS_HOUSE")).isEqualTo("\"FIAS_HOUSE\"");
    }

    @Test
    void quotesOnlyUpperCaseIdentifiers() {
        assertThat(dialect.quoteIdentifier("VALUE")).isEqualTo("\"VALUE\"");
        assertThat(dialect.quoteIdentifier("fias_house")).isEqualTo("fias_house");
        assertThat(dialect.quoteIdentifier("FiasHouse")).isEqualTo("FiasHouse");
    }

    @Test
    void asksForUtf8UnlessTheUrlChoosesAnEncoding() {
        assertThat(dialect.applyUrlTweaks("jdbc:firebirdsql://h//db.fdb"))
                .isEqualTo("jdbc:firebirdsql://h//db.fdb?encoding=UTF8");
        assertThat(dialect.applyUrlTweaks("jdbc:firebirdsql://h//db.fdb?blobBufferSize=4096"))
                .isEqualTo("jdbc:firebirdsql://h//db.fdb?blobBufferSize=4096&encoding=UTF8");
        assertThat(dialect.applyUrlTweaks("jdbc:firebirdsql://h//db.fdb?encoding=WIN1251"))
                .endsWith("?encoding=WIN1251");
        assertThat(dialect.applyUrlTweaks("jdbc:firebirdsql://h//db.fdb?charSet=Cp1251"))
                .endsWith("?charSet=Cp1251");
        assertThat(dialect.applyUrlTweaks("jdbc:firebirdsql://h//db.fdb?lc_ctype=WIN1251"))
                .endsWith("?lc_ctype=WIN1251");
    }

    @Test
    void limitsWithFetchFirstUnlessTheQueryLimitsItself() {
        assertThat(dialect.limitQuery("SELECT * FROM t;", 5)).isEqualTo("SELECT * FROM t\nFETCH FIRST 5 ROWS ONLY");
        assertThat(dialect.limitQuery("SELECT FIRST 3 * FROM t", 5)).isEqualTo("SELECT FIRST 3 * FROM t");
        assertThat(dialect.limitQuery("SELECT * FROM t ROWS 1 TO 3", 5)).isEqualTo("SELECT * FROM t ROWS 1 TO 3");
    }

    @Test
    void plansComeFromTheDriverWithoutRowEstimates() {
        assertThat(dialect.planCapture()).isEqualTo(SqlDialect.PlanCapture.DRIVER_API);
        assertThat(dialect.plannerRowEstimates()).isFalse();
        assertThatThrownBy(() -> dialect.buildExplain("SELECT 1 FROM RDB$DATABASE", false))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(dialect.histogramPercentileFunction(true)).isEqualTo("percentile_disc");
        assertThat(dialect.unusedIndexesUnsupportedReason()).contains("Firebird");
    }

    /**
     * Callers bind parameters positionally and always bind the schema, so each query must
     * consume exactly as many {@code ?} as its caller binds.
     */
    @Test
    void catalogQueriesTakeTheParametersTheirCallersBind() {
        assertParams(2, dialect::viewDefinitionQuery);       // schema, name
        assertParams(1, dialect::schemaViewsQuery);          // schema
        assertParams(2, dialect::routineSourceQuery);        // schema, name
        assertParams(2, dialect::searchObjectsQuery);        // pattern, pattern
        assertParams(2, dialect::listSequencesQuery);        // schema, schema
        assertParams(4, dialect::listRoutinesQuery);         // schema, schema, pattern, pattern
        assertParams(2, dialect::tableStatsQuery);           // schema, table
        assertParams(3, dialect::indexStatsQuery);           // schema, table, table
        assertParams(2, dialect::tableConstraintsQuery);     // schema, table
        assertParams(1, dialect::schemaConstraintsQuery);    // schema
        assertParams(2, dialect::tableTriggersQuery);        // schema, table
        assertParams(1, dialect::schemaTriggersQuery);       // schema
        assertParams(3, dialect::triggerDefinitionQuery);    // schema, table, trigger
    }

    @Test
    void histogramIsComputedFromRanks() {
        String sql = dialect.histogramQuery("\"EVENTS\"", "\"AMOUNT\"", "percentile_disc");
        assertThat(sql)
                .contains("ROW_NUMBER() OVER")
                .contains("MIN(CASE WHEN rn >= 0.5 * cnt THEN v END) AS p50")
                .contains("FROM (SELECT \"AMOUNT\" AS v FROM \"EVENTS\") b")
                .doesNotContain("WITHIN GROUP");
    }

    /** Counts {@code ?} placeholders outside {@code '...'} string literals. */
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
