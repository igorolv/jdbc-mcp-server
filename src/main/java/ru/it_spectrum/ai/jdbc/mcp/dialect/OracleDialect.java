package ru.it_spectrum.ai.jdbc.mcp.dialect;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class OracleDialect implements SqlDialect {

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.ORACLE;
    }

    @Override
    public void prepareReadOnly(Connection connection) throws SQLException {
        // Oracle ignores setReadOnly in the driver, so we enforce a server-side read-only transaction.
        // DDL bypasses transactions — that is caught by ReadOnlyGuard before we get here.
        if (!connection.isReadOnly()) {
            connection.setReadOnly(true);
        }
        try (Statement st = connection.createStatement()) {
            st.execute("SET TRANSACTION READ ONLY");
        } catch (SQLException ignore) {
            // Can fail if a transaction is already in progress — pool's autoCommit=true
            // normally makes this a no-op, so we just try our best.
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
        // Uppercase match — Oracle stores unquoted identifiers in upper case.
        return """
                SELECT owner        AS schema,
                       object_name  AS name,
                       object_type  AS type,
                       owner        AS owner
                FROM all_objects
                WHERE object_name LIKE UPPER(?)
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
                       increment_by   AS increment,
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
