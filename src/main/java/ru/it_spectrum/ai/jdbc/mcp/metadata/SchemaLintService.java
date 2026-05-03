package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
class SchemaLintService extends SchemaContextSupport {

    SchemaLintService(MetadataService metadata, StatsService stats, SqlExecutor executor, SqlDialect dialect) {
        super(metadata, stats, executor, dialect);
    }

    public Map<String, Object> schemaLint(String schema, String table, String checks,
                                          Integer maxTables, Integer maxFindings,
                                          Boolean includeInferred) throws SQLException {
        int tableLimit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        int findingLimit = clamp(maxFindings, DEFAULT_MAX_FINDINGS, 1, MAX_FINDINGS_LIMIT);
        boolean inferred = includeInferred == null || includeInferred;
        Set<String> enabledChecks = parseChecks(checks);

        Map<String, Map<String, Object>> tables = table != null && !table.isBlank()
                ? loadSingleTable(schema, table)
                : loadSchemaTables(schema, tableLimit);
        List<Map<String, Object>> inferredEdges = inferred
                ? inferRelationshipEdges(new ArrayList<>(tables.values()))
                : List.of();

        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> info : tables.values()) {
            lintTable(info, enabledChecks, findings, findingLimit);
            if (findings.size() >= findingLimit) break;
        }
        if (findings.size() < findingLimit) {
            lintRelationships(tables, inferredEdges, enabledChecks, findings, findingLimit);
        }
        if (findings.size() < findingLimit) {
            lintGraph(tables, enabledChecks, findings, findingLimit);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("table", table);
        out.put("checks", enabledChecks);
        out.put("includeInferred", inferred);
        out.put("tablesScanned", tables.size());
        out.put("findingCount", findings.size());
        out.put("truncated", findings.size() >= findingLimit);
        out.put("findings", findings);
        return out;
    }

    private void lintTable(Map<String, Object> info, Set<String> checks,
                           List<Map<String, Object>> findings, int limit) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        List<Map<String, Object>> columns = mapList(info.get("columns"));
        boolean isTable = !isView(info);

        if (checkEnabled(checks, "missingPrimaryKey") && isTable && mapValue(info.get("primaryKey")).isEmpty()) {
            addFinding(findings, limit, "HIGH", "missingPrimaryKey", schema, table, null,
                    "Table has no primary key", "Add a primary key if rows need stable identity or joins.");
        }

        if (checkEnabled(checks, "missingRemarks") && isBlank(info.get("remarks"))) {
            addFinding(findings, limit, "LOW", "missingTableRemarks", schema, table, null,
                    "Object has no table remarks/comment", "Add a table comment to improve schema discoverability.");
        }

        if (checkEnabled(checks, "wideTable") && columns.size() > 50) {
            addFinding(findings, limit, "LOW", "wideTable", schema, table, null,
                    "Table has " + columns.size() + " columns", "Review whether the table mixes multiple concerns.");
        }

        if (checkEnabled(checks, "nullableUnique")) {
            lintNullableUniqueColumns(info, findings, limit);
        }
        if (checkEnabled(checks, "unconstrainedStatus")) {
            lintUnconstrainedStatusColumns(info, findings, limit);
        }
        if (checkEnabled(checks, "orphanIdColumn")) {
            lintOrphanIdColumns(info, findings, limit);
        }
    }

    private void lintNullableUniqueColumns(Map<String, Object> info,
                                           List<Map<String, Object>> findings, int limit) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        for (Map<String, Object> unique : mapList(info.get("uniqueConstraints"))) {
            for (String columnName : stringList(unique, "columns")) {
                Map<String, Object> column = columnByName(info, columnName);
                if (Boolean.TRUE.equals(column.get("nullable"))) {
                    addFinding(findings, limit, "MEDIUM", "nullableUniqueColumn", schema, table, columnName,
                            "Unique constraint includes a nullable column",
                            "Check database NULL semantics before relying on this for uniqueness.");
                }
            }
        }
    }

    private void lintUnconstrainedStatusColumns(Map<String, Object> info,
                                                List<Map<String, Object>> findings, int limit) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        for (Map<String, Object> column : mapList(info.get("columns"))) {
            String columnName = str(column.get("name"));
            String normalized = normalizeIdentifier(columnName);
            if (!("status".equals(normalized) || "type".equals(normalized) || normalized.endsWith("_status")
                    || normalized.endsWith("_type"))) {
                continue;
            }
            if (hasCheckConstraintForColumn(info, columnName)) continue;
            addFinding(findings, limit, "LOW", "unconstrainedStatusColumn", schema, table, columnName,
                    "Status/type-like column has no CHECK constraint",
                    "Consider a CHECK constraint or lookup table so agents know valid values.");
        }
    }

    private void lintOrphanIdColumns(Map<String, Object> info,
                                     List<Map<String, Object>> findings, int limit) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        for (Map<String, Object> column : mapList(info.get("columns"))) {
            String columnName = str(column.get("name"));
            if (referenceNameFromColumn(columnName) == null || isKnownKeyColumn(info, columnName)) continue;
            addFinding(findings, limit, "LOW", "orphanIdColumn", schema, table, columnName,
                    "Column looks like a foreign key but no declared FK exists",
                    "Declare the FK or rely on schemaLint inferredRelationship findings if this is intentional.");
        }
    }

    private void lintRelationships(Map<String, Map<String, Object>> tables,
                                   List<Map<String, Object>> inferredEdges,
                                   Set<String> checks,
                                   List<Map<String, Object>> findings, int limit) {
        if (checkEnabled(checks, "fkWithoutIndex")) {
            for (Map<String, Object> info : tables.values()) {
                String schema = str(info.get("schema"));
                String table = str(info.get("name"));
                List<List<String>> indexColumns = indexColumns(info);
                for (Map<String, Object> fk : mapList(info.get("foreignKeys"))) {
                    List<String> fkColumns = stringList(fk, "columns");
                    if (isCoveredByIndex(fkColumns, indexColumns)) continue;
                    addFinding(findings, limit, "MEDIUM", "fkWithoutIndex", schema, table,
                            String.join(",", fkColumns),
                            "Foreign key has no supporting index on the child side",
                            "Create an index starting with the FK columns to improve joins and parent deletes/updates.");
                }
            }
        }

        if (checkEnabled(checks, "inferredRelationship")) {
            for (Map<String, Object> edge : inferredEdges) {
                addFinding(findings, limit, "MEDIUM", "inferredRelationship",
                        str(edge.get("fromSchema")), str(edge.get("fromTable")),
                        String.join(",", objectList(edge.get("fromColumns"))),
                        "Column naming suggests an undeclared relationship to "
                                + edge.get("toTable") + "." + edge.get("toColumns"),
                        "Declare a foreign key if this relationship is real.");
            }
        }

        if (checkEnabled(checks, "fkTypeMismatch")) {
            lintFkTypeMismatch(tables, findings, limit);
        }
    }

    private void lintFkTypeMismatch(Map<String, Map<String, Object>> tables,
                                    List<Map<String, Object>> findings, int limit) {
        for (Map<String, Object> info : tables.values()) {
            String schema = str(info.get("schema"));
            String table = str(info.get("name"));
            for (Map<String, Object> fk : mapList(info.get("foreignKeys"))) {
                String targetKey = key(str(fk.get("referencedSchema")), str(fk.get("referencedTable")));
                Map<String, Object> target = tables.get(targetKey);
                if (target == null) continue;
                List<String> left = stringList(fk, "columns");
                List<String> right = stringList(fk, "referencedColumns");
                for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                    Map<String, Object> leftColumn = columnByName(info, left.get(i));
                    Map<String, Object> rightColumn = columnByName(target, right.get(i));
                    String leftType = str(leftColumn.get("typeName"));
                    String rightType = str(rightColumn.get("typeName"));
                    if (typesCompatible(leftType, rightType)) continue;
                    addFinding(findings, limit, "HIGH", "fkTypeMismatch", schema, table, left.get(i),
                            "Foreign key column type " + leftType + " differs from referenced type " + rightType,
                            "Align FK and referenced column types to avoid casts and planner mistakes.");
                }
            }
        }
    }

    private void lintGraph(Map<String, Map<String, Object>> tables, Set<String> checks,
                           List<Map<String, Object>> findings, int limit) {
        if (!checkEnabled(checks, "isolatedTable")) return;
        Map<String, Integer> degrees = new HashMap<>();
        for (String tableKey : tables.keySet()) degrees.put(tableKey, 0);
        for (Map<String, Object> info : tables.values()) {
            String from = key(str(info.get("schema")), str(info.get("name")));
            for (Map<String, Object> edge : outgoingEdges(info)) {
                String to = key(str(edge.get("toSchema")), str(edge.get("toTable")));
                degrees.computeIfPresent(from, (k, v) -> v + 1);
                degrees.computeIfPresent(to, (k, v) -> v + 1);
            }
        }
        for (Map.Entry<String, Integer> entry : degrees.entrySet()) {
            if (entry.getValue() != 0) continue;
            Map<String, Object> info = tables.get(entry.getKey());
            addFinding(findings, limit, "LOW", "isolatedTable",
                    str(info.get("schema")), str(info.get("name")), null,
                    "Table has no declared FK relationships in the scanned set",
                    "Verify whether joins rely on naming conventions or application logic.");
        }
    }


    private Set<String> parseChecks(String checks) {
        Set<String> defaults = Set.of("missingPrimaryKey", "fkWithoutIndex", "fkTypeMismatch",
                "inferredRelationship", "nullableUnique", "unconstrainedStatus",
                "orphanIdColumn", "missingRemarks", "isolatedTable", "wideTable");
        if (checks == null || checks.isBlank()) return defaults;
        Set<String> out = new HashSet<>();
        for (String part : checks.split(",")) {
            String check = part.trim();
            if (!check.isEmpty()) out.add(check);
        }
        return out.isEmpty() ? defaults : out;
    }

    private boolean checkEnabled(Set<String> checks, String check) {
        return checks.contains(check) || checks.contains("all");
    }

    private void addFinding(List<Map<String, Object>> findings, int limit,
                            String severity, String check, String schema, String table,
                            String column, String message, String recommendation) {
        if (findings.size() >= limit) return;
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", severity);
        finding.put("check", check);
        finding.put("schema", schema);
        finding.put("table", table);
        if (column != null && !column.isBlank()) finding.put("column", column);
        finding.put("message", message);
        finding.put("recommendation", recommendation);
        findings.add(finding);
    }

    private boolean hasCheckConstraintForColumn(Map<String, Object> info, String columnName) {
        if (columnName == null) return false;
        String normalizedColumn = normalizeIdentifier(columnName);
        for (Map<String, Object> constraint : mapList(info.get("constraints"))) {
            if (!"CHECK".equalsIgnoreCase(str(constraint.get("type")))) continue;
            if (stringList(constraint, "columns").stream()
                    .anyMatch(c -> normalizedColumn.equals(normalizeIdentifier(c)))) {
                if (isNotNullCheck(constraint.get("definition"), normalizedColumn)) continue;
                return true;
            }
            Object definition = constraint.get("definition");
            if (isNotNullCheck(definition, normalizedColumn)) continue;
            if (definition != null
                    && normalizeIdentifier(String.valueOf(definition)).contains(normalizedColumn)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNotNullCheck(Object definition, String normalizedColumn) {
        if (definition == null || normalizedColumn == null || normalizedColumn.isBlank()) return false;
        String normalizedDefinition = normalizeIdentifier(String.valueOf(definition));
        return normalizedDefinition.equals(normalizedColumn + "_is_not_null")
                || normalizedDefinition.equals(normalizedColumn + "_not_null");
    }

    private List<List<String>> indexColumns(Map<String, Object> info) {
        List<List<String>> out = new ArrayList<>();
        for (Map<String, Object> index : mapList(info.get("indexes"))) {
            out.add(objectList(index.get("columns")));
        }
        return out;
    }

    private boolean isCoveredByIndex(List<String> columns, List<List<String>> indexes) {
        if (columns.isEmpty()) return true;
        List<String> expected = lowerAll(columns);
        for (List<String> index : indexes) {
            if (index.size() < expected.size()) continue;
            if (lowerAll(index.subList(0, expected.size())).equals(expected)) return true;
        }
        return false;
    }

    private List<String> lowerAll(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) out.add(value == null ? null : value.toLowerCase(Locale.ROOT));
        return out;
    }
}
