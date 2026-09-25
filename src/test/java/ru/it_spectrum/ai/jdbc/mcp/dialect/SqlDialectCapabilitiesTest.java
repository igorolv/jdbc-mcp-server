package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the engine-specific behaviour that used to live in {@code kind() == ...} branches
 * across services and tools, and now sits behind {@link SqlDialect} methods.
 */
class SqlDialectCapabilitiesTest {

    private final SqlDialect postgres = SqlDialect.forKind(DatabaseKind.POSTGRESQL);
    private final SqlDialect oracle = SqlDialect.forKind(DatabaseKind.ORACLE);
    private final SqlDialect sqlServer = SqlDialect.forKind(DatabaseKind.MSSQL);

    @Test
    void forKindReturnsMatchingDialect() {
        for (DatabaseKind kind : DatabaseKind.values()) {
            assertThat(SqlDialect.forKind(kind).kind()).isEqualTo(kind);
        }
    }

    @Test
    void quotesIdentifiersPerEngine() {
        assertThat(postgres.qualify("public", "orders")).isEqualTo("\"public\".\"orders\"");
        assertThat(oracle.qualify("APP", "ORDERS")).isEqualTo("APP.ORDERS");
        assertThat(sqlServer.qualify("dbo", "orders")).isEqualTo("[dbo].[orders]");
        assertThat(postgres.qualify(" ", "orders")).isEqualTo("\"orders\"");
        assertThat(sqlServer.qualify(null, "orders")).isEqualTo("[orders]");
    }

    @Test
    void onlySqlServerCapturesPlansThroughSessionShowplan() {
        assertThat(postgres.planCapture()).isEqualTo(SqlDialect.PlanCapture.EXPLAIN_STATEMENT);
        assertThat(oracle.planCapture()).isEqualTo(SqlDialect.PlanCapture.EXPLAIN_STATEMENT);
        assertThat(sqlServer.planCapture()).isEqualTo(SqlDialect.PlanCapture.SESSION_SHOWPLAN);
    }

    @Test
    void onlyPostgresTweaksTheUrl() {
        assertThat(postgres.applyUrlTweaks("jdbc:postgresql://h/db"))
                .isEqualTo("jdbc:postgresql://h/db?options=-c%20default_transaction_read_only%3Don");
        assertThat(oracle.applyUrlTweaks("jdbc:oracle:thin:@//h:1521/s")).isEqualTo("jdbc:oracle:thin:@//h:1521/s");
        assertThat(sqlServer.applyUrlTweaks("jdbc:sqlserver://h;databaseName=d"))
                .isEqualTo("jdbc:sqlserver://h;databaseName=d");
    }

    @Test
    void onlyOracleAddsDataSourceProperties() {
        assertThat(oracle.dataSourceProperties()).containsEntry("remarksReporting", "true");
        assertThat(postgres.dataSourceProperties()).isEmpty();
        assertThat(sqlServer.dataSourceProperties()).isEmpty();
    }

    @Test
    void histogramUsesOrderedSetAggregatesExceptOnSqlServer() {
        String standard = postgres.histogramQuery("\"s\".\"t\"", "\"c\"", "percentile_cont");
        assertThat(standard)
                .contains("percentile_cont(0.5)  WITHIN GROUP (ORDER BY \"c\") AS p50")
                .endsWith("FROM \"s\".\"t\"")
                .doesNotContain("OVER ()");
        assertThat(oracle.histogramQuery("S.T", "C", "percentile_disc")).isEqualTo(
                postgres.histogramQuery("S.T", "C", "percentile_disc"));

        String mssql = sqlServer.histogramQuery("[s].[t]", "[c]", "percentile_disc");
        assertThat(mssql)
                .contains("percentile_disc(0.5)  WITHIN GROUP (ORDER BY v) OVER () AS p50")
                .contains("SELECT [c] AS v")
                .contains("FROM [s].[t]")
                .contains("OUTER APPLY");
    }

    @Test
    void unusedIndexesIsReportedOnlyOnPostgres() {
        assertThat(postgres.unusedIndexesUnsupportedReason()).isNull();
        assertThat(oracle.unusedIndexesUnsupportedReason()).contains("DBA_INDEX_USAGE");
        assertThat(sqlServer.unusedIndexesUnsupportedReason()).contains("sys.dm_db_index_usage_stats");
    }

    @Test
    void displayNames() {
        assertThat(DatabaseKind.POSTGRESQL.displayName()).isEqualTo("PostgreSQL");
        assertThat(DatabaseKind.ORACLE.displayName()).isEqualTo("Oracle");
        assertThat(DatabaseKind.MSSQL.displayName()).isEqualTo("SQL Server");
    }
}
