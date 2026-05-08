package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Constraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaBriefService extends SchemaContextSupport {

    @Autowired
    public SchemaBriefService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                              SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public String schemaBrief(String schema, String terms, Integer maxTables) throws SQLException {
        int tableLimit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        Map<String, TableDescription> tables = loadBriefTables(schema, terms, tableLimit);
        List<Map<String, Object>> fkEdges = new ArrayList<>();
        for (TableDescription info : tables.values()) {
            fkEdges.addAll(outgoingEdges(info));
        }

        Map<String, TableDegree> degrees = tableDegrees(tables, fkEdges);
        List<TableDescription> tableList = new ArrayList<>(tables.values());
        tableList.sort((a, b) -> Integer.compare(
                degrees.getOrDefault(key(b.schema(), b.name()), TableDegree.ZERO).total(),
                degrees.getOrDefault(key(a.schema(), a.name()), TableDegree.ZERO).total()));

        StringBuilder sb = new StringBuilder();
        sb.append("Schema brief");
        if (schema != null && !schema.isBlank()) sb.append(" for ").append(schema);
        if (terms != null && !terms.isBlank()) sb.append(" (terms: ").append(terms).append(')');
        sb.append('\n');
        sb.append("- Tables scanned: ").append(tables.size()).append('\n');
        sb.append("- Declared relationships: ").append(fkEdges.size()).append('\n');

        appendTableSection(sb, "Hub tables", tableList, degrees, "hub", 8);
        appendTableSection(sb, "Fact/detail tables", tableList, degrees, "fact/detail", 10);
        appendTableSection(sb, "Lookup/reference tables", tableList, degrees, "lookup/reference", 10);
        appendRelationshipSection(sb, "Key relationships", fkEdges, 12);
        appendEnumSection(sb, tableList, 12);
        appendTableSummarySection(sb, tableList, degrees, 15);
        return sb.toString().trim();
    }

    private void appendTableSection(StringBuilder sb, String title, List<TableDescription> tables,
                                    Map<String, TableDegree> degrees, String classification, int limit) {
        List<String> lines = new ArrayList<>();
        for (TableDescription table : tables) {
            if (!classification.equals(classifyTable(table, degrees))) continue;
            lines.add("- " + qualifiedName(table) + " (" + columnCount(table) + " cols, "
                    + relationshipSummary(table, degrees) + ")");
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty()) return;
        sb.append('\n').append(title).append('\n');
        for (String line : lines) sb.append(line).append('\n');
    }

    private void appendRelationshipSection(StringBuilder sb, String title,
                                           List<Map<String, Object>> edges, int limit) {
        if (edges.isEmpty()) return;
        sb.append('\n').append(title).append('\n');
        int count = 0;
        for (Map<String, Object> edge : edges) {
            if (count++ >= limit) {
                sb.append("- ... ").append(edges.size() - limit).append(" more\n");
                break;
            }
            sb.append("- ")
                    .append(edge.get("fromTable")).append('.').append(String.join("+", objectList(edge.get("fromColumns"))))
                    .append(" -> ")
                    .append(edge.get("toTable")).append('.').append(String.join("+", objectList(edge.get("toColumns"))));
            sb.append('\n');
        }
    }

    private void appendEnumSection(StringBuilder sb, List<TableDescription> tables, int limit) {
        List<String> lines = new ArrayList<>();
        for (TableDescription table : tables) {
            for (Constraint constraint : table.constraints()) {
                if (!"CHECK".equalsIgnoreCase(str(constraint.type()))) continue;
                EnumLikeConstraint enumLike = enumLikeConstraint(table, constraint);
                if (enumLike == null) continue;
                lines.add("- " + qualifiedName(table) + "." + enumLike.column + " in "
                        + enumLike.values);
                if (lines.size() >= limit) break;
            }
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty()) return;
        sb.append('\n').append("Enum-like columns").append('\n');
        for (String line : lines) sb.append(line).append('\n');
    }

    private void appendTableSummarySection(StringBuilder sb, List<TableDescription> tables,
                                           Map<String, TableDegree> degrees, int limit) {
        if (tables.isEmpty()) return;
        sb.append('\n').append("Table notes").append('\n');
        int count = 0;
        for (TableDescription table : tables) {
            if (count++ >= limit) {
                sb.append("- ... ").append(tables.size() - limit).append(" more\n");
                break;
            }
            sb.append("- ").append(qualifiedName(table))
                    .append(": ").append(classifyTable(table, degrees))
                    .append(", PK ").append(primaryKeySummary(table))
                    .append(", columns ").append(topColumns(table, 8));
            if (!isBlank(table.remarks())) {
                sb.append(", remarks: ").append(shorten(str(table.remarks()), 80));
            }
            sb.append('\n');
        }
    }


    private EnumLikeConstraint enumLikeConstraint(TableDescription table, Constraint constraint) {
        Object definition = constraint.definition();
        if (definition == null) return null;
        String def = String.valueOf(definition);
        EnumLikeConstraint pgArray = postgresArrayEnumConstraint(table, def);
        if (pgArray != null) return pgArray;
        int inPos = def.toUpperCase(Locale.ROOT).indexOf(" IN ");
        if (inPos < 0) return null;
        int open = def.indexOf('(', inPos);
        int close = def.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return null;
        String before = def.substring(0, inPos).replace("CHECK", "")
                .replace("(", "").replace("\"", "").trim();
        String column = before.contains(" ") ? before.substring(before.lastIndexOf(' ') + 1) : before;
        if (column.isBlank()) {
            List<String> cols = constraint.columns();
            column = cols.isEmpty() ? null : cols.get(0);
        }
        if (column == null || column.isBlank() || columnByName(table, column).isEmpty()) return null;
        String valuesRaw = def.substring(open + 1, close);
        List<String> values = new ArrayList<>();
        for (String part : valuesRaw.split(",")) {
            String value = part.trim().replace("'", "").replace("\"", "");
            if (!value.isEmpty()) values.add(value);
        }
        if (values.size() < 2 || values.size() > 20) return null;
        return new EnumLikeConstraint(column, values);
    }

    private EnumLikeConstraint postgresArrayEnumConstraint(TableDescription table, String definition) {
        String upper = definition.toUpperCase(Locale.ROOT);
        int anyPos = upper.indexOf("= ANY");
        int arrayPos = upper.indexOf("ARRAY[", anyPos);
        if (anyPos < 0 || arrayPos < 0) return null;
        String before = definition.substring(0, anyPos)
                .replace("CHECK", "").replace("(", "").replace("\"", "").trim();
        String column = before.contains(" ") ? before.substring(before.lastIndexOf(' ') + 1) : before;
        if (column.isBlank() || columnByName(table, column).isEmpty()) return null;

        int open = definition.indexOf('[', arrayPos);
        int close = definition.indexOf(']', open + 1);
        if (open < 0 || close < 0 || close <= open) return null;
        List<String> values = new ArrayList<>();
        for (String part : definition.substring(open + 1, close).split(",")) {
            String value = part.trim();
            int cast = value.indexOf("::");
            if (cast >= 0) value = value.substring(0, cast);
            value = value.replace("'", "").replace("\"", "").trim();
            if (!value.isEmpty()) values.add(value);
        }
        if (values.size() < 2 || values.size() > 20) return null;
        return new EnumLikeConstraint(column, values);
    }

    private String qualifiedName(TableDescription table) {
        String schema = table.schema();
        String name = table.name();
        return schema == null || schema.isBlank() ? name : schema + "." + name;
    }

    private String relationshipSummary(TableDescription table, Map<String, TableDegree> degrees) {
        TableDegree degree = degrees.getOrDefault(key(table.schema(), table.name()), TableDegree.ZERO);
        return degree.in + " incoming, " + degree.out + " outgoing";
    }

    private String primaryKeySummary(TableDescription table) {
        List<String> pk = table.primaryKey() != null ? table.primaryKey().columns() : List.of();
        return pk.isEmpty() ? "(none)" : String.join("+", pk);
    }

    private String topColumns(TableDescription table, int limit) {
        List<String> names = new ArrayList<>();
        for (Column column : table.columns()) {
            if (names.size() >= limit) break;
            names.add(str(column.name()));
        }
        return String.join(", ", names);
    }

    private String shorten(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, Math.max(0, limit - 3)) + "...";
    }


    private record EnumLikeConstraint(String column, List<String> values) {
    }
}
