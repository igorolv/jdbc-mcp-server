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
    public String tableStatsQuery() {
        // Single-row, schema-qualified view over pg_class + pg_stat_user_tables.
        // Works for regular tables, partitioned tables, materialized views and toast owners.
        // n_live_tup / seq_scan / last_analyze are NULL for objects not tracked in pg_stat_user_tables
        // (e.g. views) — the LEFT JOIN keeps the row.
        return """
                SELECT n.nspname                                                   AS schema,
                       c.relname                                                   AS table_name,
                       c.relkind::text                                             AS relkind,
                       c.reltuples::bigint                                         AS estimated_rows,
                       pg_total_relation_size(c.oid)                               AS total_size_bytes,
                       pg_relation_size(c.oid)                                     AS table_size_bytes,
                       pg_indexes_size(c.oid)                                      AS indexes_size_bytes,
                       (pg_total_relation_size(c.oid)
                          - pg_relation_size(c.oid)
                          - pg_indexes_size(c.oid))                                AS toast_size_bytes,
                       s.n_live_tup                                                AS live_tuples,
                       s.n_dead_tup                                                AS dead_tuples,
                       CASE WHEN COALESCE(s.n_live_tup, 0) + COALESCE(s.n_dead_tup, 0) > 0
                            THEN ROUND(100.0 * s.n_dead_tup
                                       / (s.n_live_tup + s.n_dead_tup)::numeric, 2)
                            ELSE NULL
                       END                                                         AS dead_tuple_pct,
                       s.seq_scan,
                       s.seq_tup_read,
                       s.idx_scan,
                       s.idx_tup_fetch,
                       s.n_tup_ins                                                 AS tup_ins,
                       s.n_tup_upd                                                 AS tup_upd,
                       s.n_tup_del                                                 AS tup_del,
                       s.last_vacuum,
                       s.last_autovacuum,
                       s.last_analyze,
                       s.last_autoanalyze
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
                WHERE n.nspname = ?
                  AND c.relname = ?
                  AND c.relkind IN ('r', 'p', 'm', 't')
                """;
    }

    @Override
    public String indexStatsQuery() {
        // Column list is reconstructed via pg_get_indexdef per column ordinal, which
        // works for both plain-column indexes and expression indexes.
        return """
                SELECT n.nspname                                   AS schema,
                       t.relname                                   AS table_name,
                       i.relname                                   AS index_name,
                       am.amname                                   AS index_type,
                       ix.indisunique                              AS is_unique,
                       ix.indisprimary                             AS is_primary,
                       ix.indisvalid                               AS is_valid,
                       pg_relation_size(i.oid)                     AS size_bytes,
                       COALESCE(s.idx_scan, 0)                     AS idx_scans,
                       COALESCE(s.idx_tup_read, 0)                 AS idx_tup_read,
                       COALESCE(s.idx_tup_fetch, 0)                AS idx_tup_fetch,
                       (SELECT string_agg(pg_get_indexdef(ix.indexrelid, k::int, false),
                                          ', ' ORDER BY k)
                          FROM generate_series(1, ix.indnatts) AS k) AS columns,
                       pg_get_indexdef(ix.indexrelid)              AS definition
                FROM pg_class t
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_index ix    ON ix.indrelid = t.oid
                JOIN pg_class i     ON i.oid = ix.indexrelid
                JOIN pg_am am       ON am.oid = i.relam
                LEFT JOIN pg_stat_user_indexes s ON s.indexrelid = i.oid
                WHERE n.nspname = ?
                  AND (? IS NULL OR t.relname = ?)
                  AND t.relkind IN ('r', 'p', 'm')
                ORDER BY t.relname, i.relname
                """;
    }

    @Override
    public List<String> systemSchemas() {
        return List.of("pg_catalog", "information_schema", "pg_toast");
    }
}
