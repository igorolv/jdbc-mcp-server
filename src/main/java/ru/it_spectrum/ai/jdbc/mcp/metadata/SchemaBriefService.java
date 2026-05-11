package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.context.RelationshipEdge;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaBriefService extends SchemaContextSupport {

    private static final int DEFAULT_BRIEF_TABLE_LIMIT = 2_000;
    private static final int MAX_BRIEF_TABLE_LIMIT = 5_000;
    private static final int KEY_COLUMN_LIMIT = 8;
    private static final int RELATIONSHIP_LIMIT = 200;

    @Autowired
    public SchemaBriefService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                              SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public String schemaBrief(String schema, String terms, Integer maxTables) throws SQLException {
        int tableLimit = clamp(maxTables, DEFAULT_BRIEF_TABLE_LIMIT, 1, MAX_BRIEF_TABLE_LIMIT);
        List<TableEntry> listed = listBriefTables(schema, terms);
        boolean truncated = listed.size() > tableLimit;
        List<TableEntry> selected = listed.subList(0, Math.min(tableLimit, listed.size()));
        List<String> names = tableEntryNames(selected);
        Map<String, TableDescription> tables = names.isEmpty() ? Map.of() : metadata.describeTables(schema, names);
        List<RelationshipEdge> fkEdges = new ArrayList<>();
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
        sb.append("- Tables matched: ").append(listed.size()).append('\n');
        sb.append("- Tables described: ").append(tables.size());
        if (truncated) sb.append(" (truncated by maxTables=").append(tableLimit).append(')');
        sb.append('\n');
        sb.append("- Declared relationships: ").append(fkEdges.size()).append('\n');

        appendCentralTables(sb, tableList, degrees, 20);
        appendIsolatedTables(sb, tableList, degrees, 20);
        appendTableInventory(sb, tableList, degrees);
        appendRelationshipSection(sb, "Key relationships", fkEdges, RELATIONSHIP_LIMIT);
        return sb.toString().trim();
    }

    private List<TableEntry> listBriefTables(String schema, String terms) throws SQLException {
        String pattern = terms == null || terms.isBlank() ? "%" : "%" + terms + "%";
        List<TableEntry> listed = metadata.listTables(schema, pattern, parseTypes("TABLE,VIEW,MATERIALIZED VIEW"));
        if (listed.isEmpty() && terms != null && !terms.isBlank()) {
            listed = metadata.listTables(schema, "%", parseTypes("TABLE,VIEW,MATERIALIZED VIEW"));
        }
        return listed;
    }

    private void appendCentralTables(StringBuilder sb, List<TableDescription> tables,
                                     Map<String, TableDegree> degrees, int limit) {
        List<String> lines = new ArrayList<>();
        for (TableDescription table : tables) {
            TableDegree degree = degrees.getOrDefault(key(table.schema(), table.name()), TableDegree.ZERO);
            if (degree.total() <= 0) continue;
            lines.add("- " + qualifiedName(table) + " (" + columnCount(table) + " cols, "
                    + relationshipSummary(table, degrees) + ", keys " + keyColumns(table) + ")");
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty()) return;
        sb.append('\n').append("Central tables").append('\n');
        for (String line : lines) sb.append(line).append('\n');
    }

    private void appendIsolatedTables(StringBuilder sb, List<TableDescription> tables,
                                      Map<String, TableDegree> degrees, int limit) {
        List<String> lines = new ArrayList<>();
        for (TableDescription table : tables) {
            TableDegree degree = degrees.getOrDefault(key(table.schema(), table.name()), TableDegree.ZERO);
            if (degree.total() != 0) continue;
            lines.add("- " + qualifiedName(table) + " (" + columnCount(table) + " cols, keys "
                    + keyColumns(table) + ")");
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty()) return;
        sb.append('\n').append("Isolated tables").append('\n');
        for (String line : lines) sb.append(line).append('\n');
        if (lines.size() == limit) {
            int remaining = 0;
            for (TableDescription table : tables) {
                TableDegree degree = degrees.getOrDefault(key(table.schema(), table.name()), TableDegree.ZERO);
                if (degree.total() == 0) remaining++;
            }
            if (remaining > limit) sb.append("- ... ").append(remaining - limit).append(" more\n");
        }
    }

    private void appendTableInventory(StringBuilder sb, List<TableDescription> tables,
                                      Map<String, TableDegree> degrees) {
        if (tables.isEmpty()) return;
        sb.append('\n').append("Tables").append('\n');
        for (TableDescription table : tables) {
            sb.append("- ").append(qualifiedName(table))
                    .append(" [").append(str(table.type())).append("]")
                    .append(": ").append(columnCount(table)).append(" cols")
                    .append(", ").append(relationshipSummary(table, degrees))
                    .append(", PK ").append(primaryKeySummary(table))
                    .append(", keys ").append(keyColumns(table));
            if (!isBlank(table.remarks())) {
                sb.append(", remarks: ").append(shorten(str(table.remarks()), 80));
            }
            sb.append('\n');
        }
    }

    private void appendRelationshipSection(StringBuilder sb, String title,
                                           List<RelationshipEdge> edges, int limit) {
        if (edges.isEmpty()) return;
        sb.append('\n').append(title).append('\n');
        int count = 0;
        for (RelationshipEdge edge : edges) {
            if (count++ >= limit) {
                sb.append("- ... ").append(edges.size() - limit).append(" more\n");
                break;
            }
            sb.append("- ")
                    .append(edge.fromTable()).append('.').append(String.join("+", edge.fromColumns()))
                    .append(" -> ")
                    .append(edge.toTable()).append('.').append(String.join("+", edge.toColumns()));
            sb.append('\n');
        }
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

    private String keyColumns(TableDescription table) {
        Set<String> names = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        if (table.primaryKey() != null) {
            for (String column : table.primaryKey().columns()) addKeyColumn(names, ordered, column);
        }
        for (ForeignKey foreignKey : table.foreignKeys()) {
            for (String column : foreignKey.columns()) addKeyColumn(names, ordered, column);
        }
        for (Index index : table.indexes()) {
            for (String column : index.columns()) {
                if (ordered.size() >= KEY_COLUMN_LIMIT) break;
                addKeyColumn(names, ordered, column);
            }
            if (ordered.size() >= KEY_COLUMN_LIMIT) break;
        }
        for (Column column : table.columns()) {
            if (ordered.size() >= KEY_COLUMN_LIMIT) break;
            String name = str(column.name());
            if (isKeyLikeName(name)) addKeyColumn(names, ordered, name);
        }
        return ordered.isEmpty() ? "(none)" : String.join(", ", ordered);
    }

    private void addKeyColumn(Set<String> seen, List<String> ordered, String column) {
        if (column == null || column.isBlank() || ordered.size() >= KEY_COLUMN_LIMIT) return;
        if (seen.add(column.toLowerCase(java.util.Locale.ROOT))) {
            ordered.add(column);
        }
    }

    private boolean isKeyLikeName(String columnName) {
        String normalized = normalizeIdentifier(columnName);
        return "id".equals(normalized)
                || normalized.endsWith("_id")
                || normalized.endsWith("_key")
                || normalized.endsWith("_code")
                || normalized.endsWith("_no")
                || normalized.endsWith("_num")
                || "status".equals(normalized)
                || normalized.endsWith("_status")
                || "type".equals(normalized)
                || normalized.endsWith("_type");
    }

    private String shorten(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, Math.max(0, limit - 3)) + "...";
    }
}
