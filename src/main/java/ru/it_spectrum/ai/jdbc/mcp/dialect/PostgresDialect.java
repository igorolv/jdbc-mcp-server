package ru.it_spectrum.ai.jdbc.mcp.dialect;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class PostgresDialect implements SqlDialect {

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.POSTGRESQL;
    }

    @Override
    public void prepareReadOnly(Connection connection) throws SQLException {
        // The pool already marks the connection read-only; on PG this is honoured by the driver.
        // Server-side enforcement comes from default_transaction_read_only=on applied via URL options.
        if (!connection.isReadOnly()) {
            connection.setReadOnly(true);
        }
    }

    @Override
    public String limitQuery(String sql, int limit) {
        String trimmed = sql.trim();
        // If the query ends with ';', strip it so we can append LIMIT cleanly.
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains(" limit ") || lower.endsWith(" limit")) {
            return trimmed;
        }
        return trimmed + "\nLIMIT " + limit;
    }

    @Override
    public String buildExplain(String sql, boolean analyze) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        // BUFFERS requires ANALYZE; we keep output compact and side-effect-free by default.
        String options = analyze
                ? "(ANALYZE true, VERBOSE true, COSTS true, FORMAT TEXT)"
                : "(VERBOSE true, COSTS true, FORMAT TEXT)";
        return "EXPLAIN " + options + " " + trimmed;
    }

    @Override
    public String explainDisplayQuery() {
        return null;
    }

    @Override
    public String viewDefinitionQuery() {
        // One-row result: (definition)
        return """
                SELECT pg_get_viewdef(c.oid, true) AS definition
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relname = ?
                  AND c.relkind IN ('v', 'm')
                """;
    }

    @Override
    public String routineSourceQuery() {
        // One-row result: (definition)
        return """
                SELECT pg_get_functiondef(p.oid) AS definition
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = ?
                  AND p.proname = ?
                """;
    }

    @Override
    public String searchObjectsQuery() {
        // (schema, name, type, owner)
        return """
                SELECT n.nspname AS schema,
                       c.relname AS name,
                       CASE c.relkind
                           WHEN 'r' THEN 'TABLE'
                           WHEN 'p' THEN 'PARTITIONED TABLE'
                           WHEN 'v' THEN 'VIEW'
                           WHEN 'm' THEN 'MATERIALIZED VIEW'
                           WHEN 'f' THEN 'FOREIGN TABLE'
                           WHEN 'S' THEN 'SEQUENCE'
                           ELSE c.relkind::text
                       END AS type,
                       pg_get_userbyid(c.relowner) AS owner
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname ILIKE ?
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND c.relkind IN ('r','p','v','m','f','S')
                UNION ALL
                SELECT n.nspname AS schema,
                       p.proname AS name,
                       CASE p.prokind
                           WHEN 'f' THEN 'FUNCTION'
                           WHEN 'p' THEN 'PROCEDURE'
                           WHEN 'a' THEN 'AGGREGATE'
                           WHEN 'w' THEN 'WINDOW FUNCTION'
                           ELSE 'ROUTINE'
                       END AS type,
                       pg_get_userbyid(p.proowner) AS owner
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE p.proname ILIKE ?
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                ORDER BY schema, name
                LIMIT 200
                """;
    }

    @Override
    public String listSequencesQuery() {
        return """
                SELECT sequence_schema AS schema,
                       sequence_name   AS name,
                       minimum_value   AS min_value,
                       maximum_value   AS max_value,
                       increment       AS increment,
                       NULL::bigint    AS last_value
                FROM information_schema.sequences
                WHERE (? IS NULL OR sequence_schema = ?)
                ORDER BY sequence_schema, sequence_name
                """;
    }

    @Override
    public String listRoutinesQuery() {
        return """
                SELECT n.nspname AS schema,
                       p.proname AS name,
                       CASE p.prokind
                           WHEN 'f' THEN 'FUNCTION'
                           WHEN 'p' THEN 'PROCEDURE'
                           WHEN 'a' THEN 'AGGREGATE'
                           WHEN 'w' THEN 'WINDOW FUNCTION'
                           ELSE 'ROUTINE'
                       END AS kind,
                       l.lanname AS language
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                JOIN pg_language l  ON l.oid = p.prolang
                WHERE (? IS NULL OR n.nspname = ?)
                  AND (? IS NULL OR p.proname ILIKE ?)
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                ORDER BY n.nspname, p.proname
                LIMIT 500
                """;
    }

    @Override
    public List<String> systemSchemas() {
        return List.of("pg_catalog", "information_schema", "pg_toast");
    }
}
