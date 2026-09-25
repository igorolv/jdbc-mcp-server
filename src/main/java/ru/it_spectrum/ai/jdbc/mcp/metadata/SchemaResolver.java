package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.SQLException;

/**
 * Single source of truth for resolving an effective schema name.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>The argument, if non-blank.</li>
 *   <li>{@link JdbcProperties#defaultSchema()}, if configured.</li>
 *   <li>{@link SqlDialect#fallbackSchema} on a live connection — current schema for PostgreSQL
 *       and SQL Server, connecting user (uppercase) for Oracle.</li>
 * </ol>
 *
 * <p>On a schemaless engine ({@link SqlDialect#supportsSchemas()} is {@code false}) the only valid
 * answer is {@link SqlDialect#logicalSchema()}: a blank argument resolves to it, the same name in any
 * case is accepted, and any other name is rejected rather than silently answered from the one
 * namespace the database has.
 *
 * <p>Replaces the three identical private {@code resolveSchema} helpers that used to live in
 * {@code MetadataService}, {@code StatsService} and {@code DistributionService}.
 */
@Service
public class SchemaResolver {

    private final JdbcProperties properties;
    private final SqlExecutor executor;
    private final SqlDialect dialect;

    public SchemaResolver(JdbcProperties properties, SqlExecutor executor, SqlDialect dialect) {
        this.properties = properties;
        this.executor = executor;
        this.dialect = dialect;
    }

    public String resolve(String schema) throws SQLException {
        if (!dialect.supportsSchemas()) {
            String logical = dialect.logicalSchema();
            if (schema == null || schema.isBlank() || schema.trim().equalsIgnoreCase(logical)) {
                return logical;
            }
            throw new IllegalArgumentException(dialect.kind().displayName() + " has no schemas; omit "
                    + "'schema' or pass '" + logical + "' (got '" + schema + "')");
        }
        if (schema != null && !schema.isBlank()) return schema;
        if (properties.defaultSchema() != null && !properties.defaultSchema().isBlank()) {
            return properties.defaultSchema();
        }
        return executor.withConnection(dialect::fallbackSchema);
    }
}
