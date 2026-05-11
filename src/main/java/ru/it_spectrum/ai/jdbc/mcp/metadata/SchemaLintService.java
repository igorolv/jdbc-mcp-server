package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.context.RelationshipEdge;
import ru.it_spectrum.ai.jdbc.mcp.model.context.SchemaLint;
import ru.it_spectrum.ai.jdbc.mcp.model.context.SchemaLintFinding;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Constraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.UniqueConstraint;

@Service
class SchemaLintService extends SchemaContextSupport {

    SchemaLintService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                      SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public SchemaLint schemaLint(String schema, String table, String checks,
                                          Integer maxTables, Integer maxFindings) throws SQLException {
        int tableLimit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        int findingLimit = clamp(maxFindings, DEFAULT_MAX_FINDINGS, 1, MAX_FINDINGS_LIMIT);
        Set<String> enabledChecks = parseChecks(checks);

        Map<String, TableDescription> tables = table != null && !table.isBlank()
                ? loadSingleTable(schema, table)
                : loadSchemaTables(schema, tableLimit);

        List<SchemaLintFinding> findings = new ArrayList<>();
        for (TableDescription info : tables.values()) {
            lintTable(info, enabledChecks, findings, findingLimit);
            if (findings.size() >= findingLimit) break;
        }
        if (findings.size() < findingLimit) {
            lintRelationships(tables, enabledChecks, findings, findingLimit);
        }
        if (findings.size() < findingLimit) {
            lintGraph(tables, enabledChecks, findings, findingLimit);
        }

        return new SchemaLint(schema, table, enabledChecks,
                tables.size(), findings.size(),
                findings.size() >= findingLimit, findings);
    }

    private void lintTable(TableDescription info, Set<String> checks,
                           List<SchemaLintFinding> findings, int limit) {
        String schema = info.schema();
        String table = info.name();
        List<Column> columns = info.columns();
        boolean isTable = !isView(info);

        if (checkEnabled(checks, "missingPrimaryKey") && isTable && info.primaryKey() == null) {
            addFinding(findings, limit, "HIGH", "missingPrimaryKey", schema, table, null,
                    "Table has no primary key", "Add a primary key if rows need stable identity or joins.");
        }

        if (checkEnabled(checks, "missingRemarks") && isBlank(info.remarks())) {
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

    private void lintNullableUniqueColumns(TableDescription info,
                                           List<SchemaLintFinding> findings, int limit) {
        String schema = info.schema();
        String table = info.name();
        for (UniqueConstraint unique : info.uniqueConstraints()) {
            for (String columnName : unique.columns()) {
                Column column = columnByName(info, columnName);
                if (column != null && column.nullable()) {
                    addFinding(findings, limit, "MEDIUM", "nullableUniqueColumn", schema, table, columnName,
                            "Unique constraint includes a nullable column",
                            "Check database NULL semantics before relying on this for uniqueness.");
                }
            }
        }
    }

    private void lintUnconstrainedStatusColumns(TableDescription info,
                                                List<SchemaLintFinding> findings, int limit) {
        String schema = info.schema();
        String table = info.name();
        for (Column column : info.columns()) {
            String columnName = str(column.name());
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

    private void lintOrphanIdColumns(TableDescription info,
                                     List<SchemaLintFinding> findings, int limit) {
        String schema = info.schema();
        String table = info.name();
        for (Column column : info.columns()) {
            String columnName = str(column.name());
            if (referenceNameFromColumn(columnName) == null || isKnownKeyColumn(info, columnName)) continue;
            addFinding(findings, limit, "LOW", "orphanIdColumn", schema, table, columnName,
                    "Column looks like a foreign key but no declared FK exists",
                    "Declare the FK if this relationship is real.");
        }
    }

    private void lintRelationships(Map<String, TableDescription> tables,
                                   Set<String> checks,
                                   List<SchemaLintFinding> findings, int limit) {
        if (checkEnabled(checks, "fkWithoutIndex")) {
            for (TableDescription info : tables.values()) {
                String schema = info.schema();
                String table = info.name();
                List<List<String>> indexColumns = indexColumns(info);
                for (ForeignKey fk : info.foreignKeys()) {
                    List<String> fkColumns = fk.columns();
                    if (isCoveredByIndex(fkColumns, indexColumns)) continue;
                    addFinding(findings, limit, "MEDIUM", "fkWithoutIndex", schema, table,
                            String.join(",", fkColumns),
                            "Foreign key has no supporting index on the child side",
                            "Create an index starting with the FK columns to improve joins and parent deletes/updates.");
                }
            }
        }

        if (checkEnabled(checks, "fkTypeMismatch")) {
            lintFkTypeMismatch(tables, findings, limit);
        }
    }

    private void lintFkTypeMismatch(Map<String, TableDescription> tables,
                                    List<SchemaLintFinding> findings, int limit) {
        for (TableDescription info : tables.values()) {
            String schema = info.schema();
            String table = info.name();
            for (ForeignKey fk : info.foreignKeys()) {
                String targetKey = key(str(fk.referencedSchema()), str(fk.referencedTable()));
                TableDescription target = tables.get(targetKey);
                if (target == null) continue;
                List<String> left = fk.columns();
                List<String> right = fk.referencedColumns();
                for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                    Column leftColumn = columnByName(info, left.get(i));
                    Column rightColumn = columnByName(target, right.get(i));
                    if (leftColumn == null || rightColumn == null) continue;
                    String leftType = str(leftColumn.typeName());
                    String rightType = str(rightColumn.typeName());
                    if (typesCompatible(leftType, rightType)) continue;
                    addFinding(findings, limit, "HIGH", "fkTypeMismatch", schema, table, left.get(i),
                            "Foreign key column type " + leftType + " differs from referenced type " + rightType,
                            "Align FK and referenced column types to avoid casts and planner mistakes.");
                }
            }
        }
    }

    private void lintGraph(Map<String, TableDescription> tables, Set<String> checks,
                           List<SchemaLintFinding> findings, int limit) {
        if (!checkEnabled(checks, "isolatedTable")) return;
        Map<String, Integer> degrees = new HashMap<>();
        for (String tableKey : tables.keySet()) degrees.put(tableKey, 0);
        for (TableDescription info : tables.values()) {
            String from = key(info.schema(), info.name());
            for (RelationshipEdge edge : outgoingEdges(info)) {
                String to = key(edge.toSchema(), edge.toTable());
                degrees.computeIfPresent(from, (k, v) -> v + 1);
                degrees.computeIfPresent(to, (k, v) -> v + 1);
            }
        }
        for (Map.Entry<String, Integer> entry : degrees.entrySet()) {
            if (entry.getValue() != 0) continue;
            TableDescription info = tables.get(entry.getKey());
            addFinding(findings, limit, "LOW", "isolatedTable",
                    info.schema(), info.name(), null,
                    "Table has no declared FK relationships in the scanned set",
                    "Verify whether joins rely on naming conventions or application logic.");
        }
    }


    private Set<String> parseChecks(String checks) {
        Set<String> defaults = Set.of("missingPrimaryKey", "fkWithoutIndex", "fkTypeMismatch",
                "nullableUnique", "unconstrainedStatus",
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

    private void addFinding(List<SchemaLintFinding> findings, int limit,
                            String severity, String check, String schema, String table,
                            String column, String message, String recommendation) {
        if (findings.size() >= limit) return;
        findings.add(new SchemaLintFinding(
                severity,
                check,
                schema,
                table,
                column == null || column.isBlank() ? null : column,
                message,
                recommendation));
    }

    private boolean hasCheckConstraintForColumn(TableDescription info, String columnName) {
        if (columnName == null) return false;
        String normalizedColumn = normalizeIdentifier(columnName);
        for (Constraint constraint : info.constraints()) {
            if (!"CHECK".equalsIgnoreCase(str(constraint.type()))) continue;
            if (constraint.columns().stream()
                    .anyMatch(c -> normalizedColumn.equals(normalizeIdentifier(c)))) {
                if (isNotNullCheck(constraint.definition(), normalizedColumn)) continue;
                return true;
            }
            Object definition = constraint.definition();
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

    private List<List<String>> indexColumns(TableDescription info) {
        List<List<String>> out = new ArrayList<>();
        for (Index index : info.indexes()) {
            out.add(objectList(index.columns()));
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
