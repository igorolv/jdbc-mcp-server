package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerDialectTest {

    private final SqlServerDialect dialect = new SqlServerDialect();

    @Test
    void reportsMssqlKind() {
        assertThat(dialect.kind()).isEqualTo(DatabaseKind.MSSQL);
    }

    @Test
    void injectsTopForPlainSelects() {
        assertThat(dialect.limitQuery("SELECT id, name FROM dbo.customers", 10))
                .isEqualTo("SELECT TOP (10) id, name FROM dbo.customers");
    }

    @Test
    void injectsTopAfterDistinct() {
        assertThat(dialect.limitQuery("SELECT DISTINCT status FROM dbo.orders", 5))
                .isEqualTo("SELECT DISTINCT TOP (5) status FROM dbo.orders");
    }

    @Test
    void doesNotDoubleLimit() {
        assertThat(dialect.limitQuery("SELECT TOP (3) * FROM dbo.orders", 10))
                .isEqualTo("SELECT TOP (3) * FROM dbo.orders");
    }

    @Test
    void exposesCatalogQueriesWithSharedBindShapes() {
        assertThat(dialect.searchObjectsQuery()).contains("LIKE ? OR objects.[name] LIKE ?");
        assertThat(dialect.tableConstraintsQuery())
                .contains("WITH target AS")
                .contains("WHERE s.name = ?")
                .contains("AND t.name = ?");
    }
}
