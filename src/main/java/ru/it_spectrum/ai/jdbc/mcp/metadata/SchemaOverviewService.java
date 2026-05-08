package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaOverviewService extends SchemaContextSupport {

    SchemaOverviewService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                          SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public Map<String, Object> schemaOverview(String schema, String namePattern,
                                              Boolean includeViews, Boolean includeStats,
                                              Boolean includeObserved,
                                              Integer maxTables) throws SQLException {
        int limit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        boolean views = includeViews == null || includeViews;
        boolean observed = defaultIncludeObserved(includeObserved);
        String types = views ? "TABLE,VIEW,MATERIALIZED VIEW" : "TABLE";

        List<TableEntry> listed = metadata.listTables(schema, namePattern, parseTypes(types));
        boolean truncated = listed.size() > limit;
        List<TableEntry> selected = listed.subList(0, Math.min(limit, listed.size()));

        List<Map<String, Object>> tables = new ArrayList<>(selected.size());
        List<Map<String, Object>> relationships = new ArrayList<>();
        Set<String> relationshipKeys = new HashSet<>();
        Set<String> describedNamesUpper = new HashSet<>();

        for (TableEntry row : selected) {
            String tableSchema = row.schema();
            String tableName = row.name();
            if (tableName == null || tableName.isBlank()) continue;
            TableDescription described;
            try {
                described = metadata.describeTable(tableSchema, tableName);
            } catch (SQLException e) {
                tables.add(errorTable(row, e));
                continue;
            }
            tables.add(compactTable(described, Boolean.TRUE.equals(includeStats)));
            describedNamesUpper.add(upper(tableName));
            for (Map<String, Object> edge : outgoingEdges(described)) {
                addUnique(relationships, relationshipKeys, edge);
            }
        }
        decorateAndAppendObserved(relationships, describedNamesUpper, observed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("namePattern", namePattern);
        out.put("includeViews", views);
        out.put("includeStats", Boolean.TRUE.equals(includeStats));
        out.put("includeObserved", observed);
        out.put("tableCount", listed.size());
        out.put("returnedTableCount", tables.size());
        out.put("truncated", truncated);
        out.put("tables", tables);
        out.put("relationships", relationships);
        return out;
    }

    private Map<String, Object> errorTable(TableEntry listedTable, SQLException error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", listedTable.schema());
        out.put("name", listedTable.name());
        out.put("type", listedTable.type());
        out.put("remarks", listedTable.remarks());
        out.put("error", error.getMessage());
        return out;
    }
}
