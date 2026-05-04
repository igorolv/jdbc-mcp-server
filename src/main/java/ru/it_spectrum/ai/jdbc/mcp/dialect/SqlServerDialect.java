package ru.it_spectrum.ai.jdbc.mcp.dialect;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class SqlServerDialect implements SqlDialect {

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.MSSQL;
    }

    @Override
    public void prepareReadOnly(Connection connection) throws SQLException {
        // SQL Server treats Connection#setReadOnly mostly as a driver hint. The client-side
        // ReadOnlyGuard and a database read-only/login policy remain the primary protection.
        if (!connection.isReadOnly()) {
            connection.setReadOnly(true);
        }
    }

    @Override
    public String limitQuery(String sql, int limit) {
        String trimmed = stripTrailingSemicolons(sql);
        String lower = trimmed.toLowerCase();
        if (lower.contains(" top ") || lower.contains(" offset ") || lower.contains(" fetch next ")
                || lower.contains(" fetch first ")) {
            return trimmed;
        }
        if (lower.startsWith("select distinct ")) {
            return "SELECT DISTINCT TOP (" + limit + ") " + trimmed.substring("select distinct ".length());
        }
        if (lower.startsWith("select ")) {
            return "SELECT TOP (" + limit + ") " + trimmed.substring("select ".length());
        }
        return trimmed;
    }

    @Override
    public String buildExplain(String sql, boolean analyze) {
        return stripTrailingSemicolons(sql);
    }

    @Override
    public String explainDisplayQuery() {
        return null;
    }

    @Override
    public String buildStructuredExplain(String sql, boolean analyze) {
        return stripTrailingSemicolons(sql);
    }

    @Override
    public String viewDefinitionQuery() {
        return """
                SELECT m.definition AS definition
                FROM sys.views v
                JOIN sys.schemas s ON s.schema_id = v.schema_id
                JOIN sys.sql_modules m ON m.object_id = v.object_id
                WHERE s.name = ?
                  AND v.name = ?
                """;
    }

    @Override
    public String routineSourceQuery() {
        return """
                SELECT m.definition AS definition
                FROM sys.objects o
                JOIN sys.schemas s ON s.schema_id = o.schema_id
                JOIN sys.sql_modules m ON m.object_id = o.object_id
                WHERE s.name = ?
                  AND o.name = ?
                  AND o.type IN ('P', 'PC', 'FN', 'IF', 'TF', 'FS', 'FT', 'AF', 'TR')
                """;
    }

    @Override
    public String searchObjectsQuery() {
        return """
                SELECT TOP (200) *
                FROM (
                    SELECT s.name AS [schema],
                           o.name AS [name],
                           o.type_desc AS [type],
                           SCHEMA_NAME(o.schema_id) AS [owner]
                    FROM sys.objects o
                    JOIN sys.schemas s ON s.schema_id = o.schema_id
                    WHERE o.type IN ('U', 'V', 'P', 'PC', 'FN', 'IF', 'TF', 'FS', 'FT', 'AF', 'TR', 'SN')
                      AND s.name NOT IN ('sys', 'INFORMATION_SCHEMA')
                    UNION ALL
                    SELECT SCHEMA_NAME(seq.schema_id) AS [schema],
                           seq.name AS [name],
                           CAST('SEQUENCE' AS nvarchar(60)) AS [type],
                           SCHEMA_NAME(seq.schema_id) AS [owner]
                    FROM sys.sequences seq
                ) objects
                WHERE objects.[name] LIKE ? OR objects.[name] LIKE ?
                ORDER BY objects.[schema], objects.[name]
                """;
    }

    @Override
    public String listSequencesQuery() {
        return """
                SELECT SCHEMA_NAME(schema_id) AS [schema],
                       name AS [name],
                       minimum_value AS min_value,
                       maximum_value AS max_value,
                       increment AS [increment],
                       current_value AS last_value
                FROM sys.sequences
                WHERE (? IS NULL OR SCHEMA_NAME(schema_id) = ?)
                ORDER BY SCHEMA_NAME(schema_id), name
                """;
    }

    @Override
    public String listRoutinesQuery() {
        return """
                SELECT TOP (500)
                       s.name AS [schema],
                       o.name AS [name],
                       o.type_desc AS kind,
                       CAST('T-SQL' AS nvarchar(20)) AS language
                FROM sys.objects o
                JOIN sys.schemas s ON s.schema_id = o.schema_id
                WHERE o.type IN ('P', 'PC', 'FN', 'IF', 'TF', 'FS', 'FT', 'AF')
                  AND (? IS NULL OR s.name = ?)
                  AND (? IS NULL OR o.name LIKE ?)
                ORDER BY s.name, o.name
                """;
    }

    @Override
    public String tableStatsQuery() {
        return """
                SELECT s.name AS [schema],
                       t.name AS table_name,
                       CAST('U' AS varchar(10)) AS relkind,
                       COALESCE(r.estimated_rows, 0) AS estimated_rows,
                       COALESCE(sz.total_size_bytes, 0) AS total_size_bytes,
                       COALESCE(sz.table_size_bytes, 0) AS table_size_bytes,
                       COALESCE(sz.indexes_size_bytes, 0) AS indexes_size_bytes,
                       t.create_date,
                       t.modify_date,
                       t.is_memory_optimized,
                       t.temporal_type_desc,
                       t.is_filetable
                FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                LEFT JOIN (
                    SELECT object_id,
                           SUM(CASE WHEN index_id IN (0, 1) THEN rows ELSE 0 END) AS estimated_rows
                    FROM sys.partitions
                    GROUP BY object_id
                ) r ON r.object_id = t.object_id
                LEFT JOIN (
                    SELECT p.object_id,
                           SUM(COALESCE(au.total_pages, 0)) * 8192 AS total_size_bytes,
                           SUM(CASE WHEN p.index_id IN (0, 1) THEN COALESCE(au.total_pages, 0) ELSE 0 END) * 8192 AS table_size_bytes,
                           SUM(CASE WHEN p.index_id > 1 THEN COALESCE(au.total_pages, 0) ELSE 0 END) * 8192 AS indexes_size_bytes
                    FROM sys.partitions p
                    LEFT JOIN sys.allocation_units au
                      ON (au.type IN (1, 3) AND au.container_id = p.hobt_id)
                      OR (au.type = 2 AND au.container_id = p.partition_id)
                    GROUP BY p.object_id
                ) sz ON sz.object_id = t.object_id
                WHERE s.name = ?
                  AND t.name = ?
                """;
    }

    @Override
    public String indexStatsQuery() {
        return """
                SELECT s.name AS [schema],
                       t.name AS table_name,
                       i.name AS index_name,
                       i.type_desc AS index_type,
                       i.is_unique AS is_unique,
                       i.is_primary_key AS is_primary,
                       CASE WHEN i.is_disabled = 0 THEN CAST(1 AS bit) ELSE CAST(0 AS bit) END AS is_valid,
                       COALESCE(sz.size_bytes, 0) AS size_bytes,
                       CAST(NULL AS bigint) AS idx_scans,
                       CAST(NULL AS bigint) AS idx_tup_read,
                       CAST(NULL AS bigint) AS idx_tup_fetch,
                       STUFF((
                           SELECT ',' + c.name
                           FROM sys.index_columns ic
                           JOIN sys.columns c
                             ON c.object_id = ic.object_id
                            AND c.column_id = ic.column_id
                           WHERE ic.object_id = i.object_id
                             AND ic.index_id = i.index_id
                             AND ic.is_included_column = 0
                           ORDER BY ic.key_ordinal
                           FOR XML PATH(''), TYPE
                       ).value('.', 'nvarchar(max)'), 1, 1, '') AS columns,
                       i.filter_definition AS definition,
                       i.is_disabled,
                       i.has_filter,
                       i.fill_factor
                FROM sys.indexes i
                JOIN sys.tables t ON t.object_id = i.object_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                LEFT JOIN (
                    SELECT p.object_id,
                           p.index_id,
                           SUM(COALESCE(au.total_pages, 0)) * 8192 AS size_bytes
                    FROM sys.partitions p
                    LEFT JOIN sys.allocation_units au
                      ON (au.type IN (1, 3) AND au.container_id = p.hobt_id)
                      OR (au.type = 2 AND au.container_id = p.partition_id)
                    GROUP BY p.object_id, p.index_id
                ) sz ON sz.object_id = i.object_id AND sz.index_id = i.index_id
                WHERE s.name = ?
                  AND (? IS NULL OR t.name = ?)
                  AND i.index_id > 0
                  AND i.name IS NOT NULL
                ORDER BY t.name, i.name
                """;
    }

    @Override
    public String columnCommentsQuery() {
        return """
                SELECT c.name AS column_name,
                       CAST(ep.value AS nvarchar(max)) AS comment
                FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                JOIN sys.columns c ON c.object_id = t.object_id
                LEFT JOIN sys.extended_properties ep
                  ON ep.major_id = c.object_id
                 AND ep.minor_id = c.column_id
                 AND ep.name = 'MS_Description'
                WHERE s.name = ?
                  AND t.name = ?
                ORDER BY c.column_id
                """;
    }

    @Override
    public String columnDefaultsQuery() {
        return """
                SELECT c.name AS column_name,
                       dc.definition AS default_value
                FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                JOIN sys.columns c ON c.object_id = t.object_id
                LEFT JOIN sys.default_constraints dc
                  ON dc.parent_object_id = c.object_id
                 AND dc.parent_column_id = c.column_id
                WHERE s.name = ?
                  AND t.name = ?
                ORDER BY c.column_id
                """;
    }

    @Override
    public String tableConstraintsQuery() {
        return """
                WITH target AS (
                    SELECT t.object_id
                    FROM sys.tables t
                    JOIN sys.schemas s ON s.schema_id = t.schema_id
                    WHERE s.name = ?
                      AND t.name = ?
                )
                SELECT kc.name AS name,
                       CASE kc.type WHEN 'PK' THEN 'PRIMARY_KEY' ELSE 'UNIQUE' END AS type,
                       STUFF((
                           SELECT ',' + c.name
                           FROM sys.index_columns ic
                           JOIN sys.columns c
                             ON c.object_id = ic.object_id
                            AND c.column_id = ic.column_id
                           WHERE ic.object_id = kc.parent_object_id
                             AND ic.index_id = kc.unique_index_id
                             AND ic.is_included_column = 0
                           ORDER BY ic.key_ordinal
                           FOR XML PATH(''), TYPE
                       ).value('.', 'nvarchar(max)'), 1, 1, '') AS columns,
                       CAST(NULL AS nvarchar(max)) AS definition,
                       CAST(NULL AS nvarchar(128)) AS referenced_schema,
                       CAST(NULL AS nvarchar(128)) AS referenced_table,
                       CAST(NULL AS nvarchar(max)) AS referenced_columns
                FROM sys.key_constraints kc
                JOIN target ON target.object_id = kc.parent_object_id
                WHERE kc.type IN ('PK', 'UQ')
                UNION ALL
                SELECT fk.name AS name,
                       CAST('FOREIGN_KEY' AS varchar(20)) AS type,
                       STUFF((
                           SELECT ',' + pc.name
                           FROM sys.foreign_key_columns fkc
                           JOIN sys.columns pc
                             ON pc.object_id = fkc.parent_object_id
                            AND pc.column_id = fkc.parent_column_id
                           WHERE fkc.constraint_object_id = fk.object_id
                           ORDER BY fkc.constraint_column_id
                           FOR XML PATH(''), TYPE
                       ).value('.', 'nvarchar(max)'), 1, 1, '') AS columns,
                       CAST(NULL AS nvarchar(max)) AS definition,
                       rs.name AS referenced_schema,
                       rt.name AS referenced_table,
                       STUFF((
                           SELECT ',' + rc.name
                           FROM sys.foreign_key_columns fkc
                           JOIN sys.columns rc
                             ON rc.object_id = fkc.referenced_object_id
                            AND rc.column_id = fkc.referenced_column_id
                           WHERE fkc.constraint_object_id = fk.object_id
                           ORDER BY fkc.constraint_column_id
                           FOR XML PATH(''), TYPE
                       ).value('.', 'nvarchar(max)'), 1, 1, '') AS referenced_columns
                FROM sys.foreign_keys fk
                JOIN target ON target.object_id = fk.parent_object_id
                JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
                JOIN sys.schemas rs ON rs.schema_id = rt.schema_id
                UNION ALL
                SELECT cc.name AS name,
                       CAST('CHECK' AS varchar(20)) AS type,
                       CASE WHEN cc.parent_column_id > 0 THEN COL_NAME(cc.parent_object_id, cc.parent_column_id) ELSE NULL END AS columns,
                       cc.definition AS definition,
                       CAST(NULL AS nvarchar(128)) AS referenced_schema,
                       CAST(NULL AS nvarchar(128)) AS referenced_table,
                       CAST(NULL AS nvarchar(max)) AS referenced_columns
                FROM sys.check_constraints cc
                JOIN target ON target.object_id = cc.parent_object_id
                ORDER BY name
                """;
    }

    @Override
    public String tableTriggersQuery() {
        return """
                SELECT s.name AS [schema],
                       t.name AS table_name,
                       tr.name AS name,
                       CASE WHEN tr.is_instead_of_trigger = 1 THEN 'INSTEAD OF' ELSE 'AFTER' END AS timing,
                       STUFF(CONCAT(
                           CASE WHEN OBJECTPROPERTY(tr.object_id, 'ExecIsInsertTrigger') = 1 THEN ',INSERT' ELSE '' END,
                           CASE WHEN OBJECTPROPERTY(tr.object_id, 'ExecIsUpdateTrigger') = 1 THEN ',UPDATE' ELSE '' END,
                           CASE WHEN OBJECTPROPERTY(tr.object_id, 'ExecIsDeleteTrigger') = 1 THEN ',DELETE' ELSE '' END
                       ), 1, 1, '') AS events,
                       CASE WHEN tr.is_disabled = 0 THEN CAST(1 AS bit) ELSE CAST(0 AS bit) END AS enabled,
                       OBJECT_DEFINITION(tr.object_id) AS definition
                FROM sys.triggers tr
                JOIN sys.tables t ON t.object_id = tr.parent_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                WHERE s.name = ?
                  AND t.name = ?
                ORDER BY tr.name
                """;
    }

    @Override
    public String triggerDefinitionQuery() {
        return """
                SELECT OBJECT_DEFINITION(tr.object_id) AS definition
                FROM sys.triggers tr
                JOIN sys.tables t ON t.object_id = tr.parent_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                WHERE s.name = ?
                  AND t.name = ?
                  AND tr.name = ?
                """;
    }

    @Override
    public List<String> systemSchemas() {
        return List.of("sys", "INFORMATION_SCHEMA", "guest",
                "db_owner", "db_accessadmin", "db_securityadmin", "db_ddladmin",
                "db_backupoperator", "db_datareader", "db_datawriter",
                "db_denydatareader", "db_denydatawriter");
    }

    @Override
    public String fallbackSchema(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT SCHEMA_NAME()")) {
            if (rs.next()) {
                String schema = rs.getString(1);
                if (schema != null && !schema.isBlank()) return schema;
            }
        }
        String schema = connection.getSchema();
        return schema == null || schema.isBlank() ? "dbo" : schema;
    }

    private static String stripTrailingSemicolons(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
