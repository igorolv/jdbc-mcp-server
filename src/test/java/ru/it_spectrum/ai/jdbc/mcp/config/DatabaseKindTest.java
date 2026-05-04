package ru.it_spectrum.ai.jdbc.mcp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseKindTest {

    @Test
    void detectsPostgresql() {
        assertThat(DatabaseKind.fromUrl("jdbc:postgresql://host:5432/db")).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(DatabaseKind.fromUrl("JDBC:POSTGRESQL://host/db")).isEqualTo(DatabaseKind.POSTGRESQL);
    }

    @Test
    void detectsOracle() {
        assertThat(DatabaseKind.fromUrl("jdbc:oracle:thin:@//host:1521/svc")).isEqualTo(DatabaseKind.ORACLE);
        assertThat(DatabaseKind.fromUrl("jdbc:oracle:oci:@TNS_NAME")).isEqualTo(DatabaseKind.ORACLE);
    }

    @Test
    void detectsSqlServer() {
        assertThat(DatabaseKind.fromUrl("jdbc:sqlserver://host:1433;databaseName=db"))
                .isEqualTo(DatabaseKind.MSSQL);
        assertThat(DatabaseKind.fromUrl("JDBC:SQLSERVER://host;databaseName=db"))
                .isEqualTo(DatabaseKind.MSSQL);
    }

    @Test
    void rejectsUnsupportedUrls() {
        assertThatThrownBy(() -> DatabaseKind.fromUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseKind.fromUrl(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseKind.fromUrl("jdbc:mysql://host/db"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyDialectUrlTweaksAddsReadOnlyOptions() {
        String out = DataSourceConfig.applyDialectUrlTweaks(
                "jdbc:postgresql://host/db", DatabaseKind.POSTGRESQL);
        assertThat(out).contains("default_transaction_read_only");
    }

    @Test
    void applyDialectUrlTweaksLeavesExistingOptions() {
        String url = "jdbc:postgresql://host/db?options=-c%20foo%3Dbar";
        assertThat(DataSourceConfig.applyDialectUrlTweaks(url, DatabaseKind.POSTGRESQL)).isEqualTo(url);
    }

    @Test
    void applyDialectUrlTweaksSkipsNonPostgres() {
        String url = "jdbc:oracle:thin:@//host/svc";
        assertThat(DataSourceConfig.applyDialectUrlTweaks(url, DatabaseKind.ORACLE)).isEqualTo(url);
        String sqlServer = "jdbc:sqlserver://host:1433;databaseName=db";
        assertThat(DataSourceConfig.applyDialectUrlTweaks(sqlServer, DatabaseKind.MSSQL)).isEqualTo(sqlServer);
    }
}
