package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/**
 * Test helper that builds an isolated in-memory H2 catalog DataSource with both the usage-catalog
 * and structure-snapshot schemas applied — the unit-test analogue of {@link CatalogDataSourceConfig}
 * (which is file-backed in production).
 */
public final class CatalogTestSupport {

    private CatalogTestSupport() {
    }

    public static DataSource inMemoryCatalog() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:catalog_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        apply(ds, "usage-catalog-schema.sql");
        apply(ds, "structure-snapshot-schema.sql");
        return ds;
    }

    private static void apply(DataSource ds, String resource) throws Exception {
        String ddl;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(ddl)) {
                if (!statement.isBlank()) stmt.execute(statement);
            }
        }
    }

    private static String[] splitStatements(String ddl) {
        StringBuilder out = new StringBuilder(ddl.length());
        for (String line : ddl.split("\\R", -1)) {
            int idx = line.indexOf("--");
            out.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
        }
        return out.toString().split(";\\s*\\R*");
    }
}
