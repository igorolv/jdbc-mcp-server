package ru.it_spectrum.ai.jdbc.mcp.dialect;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class OracleDialect implements SqlDialect {

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.ORACLE;
    }

    @Override
    public void prepareReadOnly(Connection connection) throws SQLException {
        // Oracle's JDBC driver mostly treats setReadOnly as advisory. The primary protection for
        // user SQL is ReadOnlyGuard; this is just a cheap best-effort hint for tools / pools.
        if (!connection.isReadOnly()) {
            connection.setReadOnly(true);
        }
    }

    @Override
    public String limitQuery(String sql, int limit) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains("fetch first") || lower.contains("fetch next") || lower.contains("rownum")) {
            return trimmed;
        }
        return trimmed + "\nFETCH FIRST " + limit + " ROWS ONLY";
    }

    @Override
    public String buildExplain(String sql, boolean analyze) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        // Oracle populates PLAN_TABLE without executing the query. "analyze" is ignored.
        return "EXPLAIN PLAN FOR " + trimmed;
    }

    @Override
    public String explainDisplayQuery() {
        return "SELECT plan_table_output AS plan FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, NULL, 'ALL'))";
    }

    @Override
    public String buildStructuredExplain(String sql, boolean analyze) {
        // Oracle populates PLAN_TABLE statically regardless of `analyze` — no actual execution.
        return buildExplain(sql, analyze);
    }

    @Override
    public String structuredPlanQuery() {
        // Only the columns the parser reasons over. Reading the most recently-populated plan
        // from the session's PLAN_TABLE (ordered by plan_id, id) — EXPLAIN PLAN assigns a fresh
        // plan_id per call, so taking MAX(plan_id) gives us the just-generated plan.
        return """
                SELECT id, parent_id, operation, options, object_owner, object_name,
                       cardinality, cost, bytes, cpu_cost, io_cost, time,
                       access_predicates, filter_predicates, depth
                FROM plan_table
                WHERE plan_id = (SELECT MAX(plan_id) FROM plan_table)
                ORDER BY id
                """;
    }

    @Override
    public String viewDefinitionQuery() {
        // TEXT_VC is a VARCHAR2 version of TEXT (LONG); fall back to TEXT if needed.
        return """
                SELECT text AS definition
                FROM all_views
                WHERE owner = UPPER(?)
                  AND view_name = UPPER(?)
                """;
    }

    @Override
    public String routineSourceQuery() {
        // Concatenates source lines in order.
        return """
                SELECT text AS definition
                FROM all_source
                WHERE owner = UPPER(?)
                  AND name  = UPPER(?)
                  AND type IN ('FUNCTION', 'PROCEDURE', 'PACKAGE', 'PACKAGE BODY', 'TYPE', 'TYPE BODY')
                ORDER BY line
                """;
    }

    @Override
    public String searchObjectsQuery() {
        // Keep the bind shape aligned with PostgreSQL's searchObjectsQuery(): the shared caller
        // passes the same pattern twice (relations + routines there). Oracle can search all
        // supported object types from ALL_OBJECTS in one query, so both placeholders intentionally
        // use the same object_name predicate.
        return """
                SELECT owner        AS schema,
                       object_name  AS name,
                       object_type  AS type,
                       owner        AS owner
                FROM all_objects
                WHERE (object_name LIKE UPPER(?) OR object_name LIKE UPPER(?))
                  AND object_type IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW', 'SEQUENCE',
                                      'FUNCTION', 'PROCEDURE', 'PACKAGE', 'TYPE', 'SYNONYM')
                  AND owner NOT IN ('SYS', 'SYSTEM', 'CTXSYS', 'MDSYS', 'XDB', 'GSMADMIN_INTERNAL',
                                    'DBSNMP', 'OUTLN', 'APPQOSSYS', 'AUDSYS', 'ORDSYS', 'OJVMSYS',
                                    'DVSYS', 'LBACSYS', 'DBSFWUSER', 'REMOTE_SCHEDULER_AGENT',
                                    'ORACLE_OCM', 'SI_INFORMTN_SCHEMA', 'WMSYS')
                ORDER BY owner, object_name
                FETCH FIRST 200 ROWS ONLY
                """;
    }

    @Override
    public String listSequencesQuery() {
        return """
                SELECT sequence_owner AS schema,
                       sequence_name  AS name,
                       min_value      AS min_value,
                       max_value      AS max_value,
                       increment_by   AS "increment",
                       last_number    AS last_value
                FROM all_sequences
                WHERE (? IS NULL OR sequence_owner = UPPER(?))
                ORDER BY sequence_owner, sequence_name
                """;
    }

    @Override
    public String listRoutinesQuery() {
        return """
                SELECT owner       AS schema,
                       object_name AS name,
                       object_type AS kind,
                       'PL/SQL'    AS language
                FROM all_objects
                WHERE object_type IN ('FUNCTION', 'PROCEDURE', 'PACKAGE')
                  AND (? IS NULL OR owner = UPPER(?))
                  AND (? IS NULL OR object_name LIKE UPPER(?))
                ORDER BY owner, object_name
                FETCH FIRST 500 ROWS ONLY
                """;
    }

    @Override
    public String tableStatsQuery() {
        // Pure ALL_TABLES — does not hit DBA_SEGMENTS. Size information is fetched separately
        // via segmentSizeQuery() so we can degrade gracefully when DBA_SEGMENTS is not accessible.
        return """
                SELECT t.owner            AS schema,
                       t.table_name       AS table_name,
                       t.num_rows         AS estimated_rows,
                       t.blocks           AS blocks,
                       t.empty_blocks     AS empty_blocks,
                       t.avg_row_len      AS avg_row_len,
                       t.chain_cnt        AS chain_count,
                       t.last_analyzed    AS last_analyzed,
                       t.sample_size      AS sample_size,
                       t.partitioned      AS partitioned,
                       t.temporary        AS temporary,
                       t.global_stats     AS global_stats,
                       t.user_stats       AS user_stats,
                       t.compression      AS compression
                FROM all_tables t
                WHERE t.owner = UPPER(?)
                  AND t.table_name = UPPER(?)
                """;
    }

    @Override
    public String indexStatsQuery() {
        // Columns intentionally aligned with PostgreSQL's indexStatsQuery so downstream aggregation
        // (redundant / unused indexes) can work on a uniform shape:
        //   schema, table_name, index_name, index_type, is_unique, is_primary,
        //   size_bytes, idx_scans, columns
        // Oracle-specific extras (num_rows, distinct_keys, clustering_factor, blevel, ...)
        // follow the common prefix.
        // CLUSTERING_FACTOR vs NUM_ROWS is the classic selectivity / clustering signal;
        // LAST_ANALYZED indicates stats freshness.
        // size_bytes and idx_scans are NULL on Oracle: the first requires DBA_SEGMENTS
        // (fetched separately for tables), the second is not exposed in ALL_INDEXES.
        return """
                SELECT i.owner                                   AS schema,
                       i.table_name                              AS table_name,
                       i.index_name                              AS index_name,
                       i.index_type                              AS index_type,
                       CASE WHEN i.uniqueness = 'UNIQUE' THEN 'Y' ELSE 'N' END AS is_unique,
                       CASE WHEN (SELECT MAX(cn.constraint_type)
                                  FROM all_constraints cn
                                  WHERE cn.owner = i.owner
                                    AND cn.index_name = i.index_name
                                    AND cn.constraint_type IN ('P', 'U')) = 'P'
                            THEN 'Y' ELSE 'N' END               AS is_primary,
                       CAST(NULL AS NUMBER)                      AS size_bytes,
                       CAST(NULL AS NUMBER)                      AS idx_scans,
                       (SELECT LISTAGG(c.column_name, ',')
                               WITHIN GROUP (ORDER BY c.column_position)
                        FROM all_ind_columns c
                        WHERE c.index_owner = i.owner
                          AND c.index_name  = i.index_name)      AS columns,
                       i.status                                   AS status,
                       i.num_rows                                 AS num_rows,
                       i.distinct_keys                            AS distinct_keys,
                       i.clustering_factor                        AS clustering_factor,
                       i.leaf_blocks                              AS leaf_blocks,
                       i.blevel                                   AS blevel,
                       i.last_analyzed                            AS last_analyzed,
                       (SELECT MAX(cn.constraint_type)
                        FROM all_constraints cn
                        WHERE cn.owner = i.owner
                          AND cn.index_name = i.index_name
                          AND cn.constraint_type IN ('P', 'U'))   AS constraint_type
                FROM all_indexes i
                WHERE i.owner = UPPER(?)
                  AND (? IS NULL OR i.table_name = UPPER(?))
                ORDER BY i.table_name, i.index_name
                """;
    }

    @Override
    public String segmentSizeQuery() {
        // Best-effort segment size. Requires DBA_SEGMENTS privilege (commonly granted via
        // SELECT_CATALOG_ROLE). If it fails, StatsService logs and skips size info.
        return """
                SELECT SUM(bytes) AS segment_bytes
                FROM dba_segments
                WHERE owner = UPPER(?)
                  AND segment_name = UPPER(?)
                """;
    }

    @Override
    public String columnCommentsQuery() {
        // ALL_COL_COMMENTS has VARCHAR2 remarks — no LONG issue here.
        // Using single-line format to avoid any JDBC driver parsing quirks.
        return "SELECT column_name, comments FROM all_col_comments WHERE owner = UPPER(?) AND table_name = UPPER(?)";
    }

    @Override
    public String columnDefaultsQuery() {
        // DATA_DEFAULT is LONG — DBMS_XMLGEN.GETXMLTYPE converts it to XML CLOB, then EXTRACTVALUE
        // reads it as a VARCHAR string. Safe for all Oracle versions.
        return """
                SELECT c.column_name,
                       CASE
                           WHEN c.default_length IS NULL THEN NULL
                           ELSE EXTRACTVALUE(
                                   DBMS_XMLGEN.GETXMLTYPE(
                                       'select data_default from user_tab_columns where table_name = '''
                                       || c.table_name
                                       || ''' and column_name = '''
                                       || c.column_name
                                       || '''' ),
                                   '//text()' )
                       END AS default_value
                FROM all_tab_columns c
                WHERE c.owner = UPPER(?)
                  AND c.table_name = UPPER(?)
                """;
    }

    @Override
    public String tableConstraintsQuery() {
        return """
                SELECT c.constraint_name AS name,
                       CASE c.constraint_type
                           WHEN 'P' THEN 'PRIMARY_KEY'
                           WHEN 'U' THEN 'UNIQUE'
                           WHEN 'R' THEN 'FOREIGN_KEY'
                           WHEN 'C' THEN 'CHECK'
                           WHEN 'V' THEN 'CHECK_OPTION'
                           WHEN 'O' THEN 'READ_ONLY_VIEW'
                           ELSE c.constraint_type
                       END AS type,
                       (SELECT LISTAGG(cc.column_name, ',') WITHIN GROUP (ORDER BY cc.position)
                        FROM all_cons_columns cc
                        WHERE cc.owner = c.owner
                          AND cc.constraint_name = c.constraint_name) AS columns,
                       CASE
                           WHEN c.constraint_type = 'C' THEN c.search_condition_vc
                           ELSE NULL
                       END AS definition,
                       rc.owner AS referenced_schema,
                       rc.table_name AS referenced_table,
                       (SELECT LISTAGG(rcc.column_name, ',') WITHIN GROUP (ORDER BY rcc.position)
                        FROM all_cons_columns fcc
                        JOIN all_cons_columns rcc
                          ON rcc.owner = rc.owner
                         AND rcc.constraint_name = rc.constraint_name
                         AND rcc.position = fcc.position
                        WHERE fcc.owner = c.owner
                          AND fcc.constraint_name = c.constraint_name) AS referenced_columns
                FROM all_constraints c
                LEFT JOIN all_constraints rc
                  ON rc.owner = c.r_owner
                 AND rc.constraint_name = c.r_constraint_name
                WHERE c.owner = UPPER(?)
                  AND c.table_name = UPPER(?)
                  AND c.constraint_type IN ('P', 'U', 'R', 'C', 'V', 'O')
                ORDER BY c.constraint_name
                """;
    }

    @Override
    public String tableTriggersQuery() {
        return """
                SELECT owner AS schema,
                       table_name AS table_name,
                       trigger_name AS name,
                       CASE
                           WHEN trigger_type LIKE 'BEFORE%' THEN 'BEFORE'
                           WHEN trigger_type LIKE 'AFTER%' THEN 'AFTER'
                           WHEN trigger_type LIKE 'INSTEAD OF%' THEN 'INSTEAD OF'
                           ELSE trigger_type
                       END AS timing,
                       triggering_event AS events,
                       CASE WHEN status = 'ENABLED' THEN 'true' ELSE 'false' END AS enabled,
                       description AS definition
                FROM all_triggers
                WHERE owner = UPPER(?)
                  AND table_name = UPPER(?)
                ORDER BY trigger_name
                """;
    }

    @Override
    public String triggerDefinitionQuery() {
        return """
                SELECT text AS definition
                FROM all_source
                WHERE owner = UPPER(?)
                  AND (? IS NULL OR 1 = 1)
                  AND name = UPPER(?)
                  AND type = 'TRIGGER'
                ORDER BY line
                """;
    }

    @Override
    public List<String> systemSchemas() {
        return List.of("SYS", "SYSTEM", "CTXSYS", "MDSYS", "XDB", "GSMADMIN_INTERNAL",
                "DBSNMP", "OUTLN", "APPQOSSYS", "AUDSYS", "ORDSYS", "OJVMSYS", "DVSYS",
                "LBACSYS", "DBSFWUSER", "REMOTE_SCHEDULER_AGENT", "ORACLE_OCM",
                "SI_INFORMTN_SCHEMA", "WMSYS");
    }

    @Override
    public String fallbackSchema(Connection connection) throws SQLException {
        // In Oracle, the current user is the default schema.
        String u = connection.getMetaData().getUserName();
        return u == null ? null : u.toUpperCase();
    }
}
