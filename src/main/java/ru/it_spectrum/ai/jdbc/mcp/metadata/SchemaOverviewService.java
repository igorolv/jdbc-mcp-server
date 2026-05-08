package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
class SchemaOverviewService extends SchemaContextSupport {

    SchemaOverviewService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                          SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public Map<String, Object> schemaOverview(String schema, String namePattern,
                                              Boolean includeViews, Boolean includeStats,
                                              Boolean includeInferred, Boolean includeObserved,
                                              Integer maxTables) throws SQLException {
        int limit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        boolean views = includeViews == null || includeViews;
        boolean inferred = includeInferred == null || includeInferred;
        boolean observed = defaultIncludeObserved(includeObserved);
        String types = views ? "TABLE,VIEW,MATERIALIZED VIEW" : "TABLE";

        List<Map<String, Object>> listed = metadata.listTables(schema, namePattern, parseTypes(types));
        boolean truncated = listed.size() > limit;
        List<Map<String, Object>> selected = listed.subList(0, Math.min(limit, listed.size()));

        List<Map<String, Object>> tables = new ArrayList<>(selected.size());
        List<Map<String, Object>> describedTables = new ArrayList<>(selected.size());
        List<Map<String, Object>> relationships = new ArrayList<>();
        Set<String> relationshipKeys = new HashSet<>();
        Set<String> describedNamesUpper = new HashSet<>();

        for (Map<String, Object> row : selected) {
            String tableSchema = str(row.get("schema"));
            String tableName = str(row.get("name"));
            if (tableName == null || tableName.isBlank()) continue;
            Map<String, Object> described;
            try {
                described = metadata.describeTable(tableSchema, tableName);
            } catch (SQLException e) {
                tables.add(errorTable(row, e));
                continue;
            }
            describedTables.add(described);
            tables.add(compactTable(described, Boolean.TRUE.equals(includeStats)));
            describedNamesUpper.add(upper(tableName));
            for (Map<String, Object> edge : outgoingEdges(described)) {
                addUnique(relationships, relationshipKeys, edge);
            }
        }
        if (inferred) {
            for (Map<String, Object> edge : inferRelationshipEdges(describedTables)) {
                addUnique(relationships, relationshipKeys, edge);
            }
        }
        decorateAndAppendObserved(relationships, describedNamesUpper, observed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("namePattern", namePattern);
        out.put("includeViews", views);
        out.put("includeStats", Boolean.TRUE.equals(includeStats));
        out.put("includeInferred", inferred);
        out.put("includeObserved", observed);
        out.put("tableCount", listed.size());
        out.put("returnedTableCount", tables.size());
        out.put("truncated", truncated);
        out.put("tables", tables);
        out.put("relationships", relationships);
        return out;
    }

    private Map<String, Object> errorTable(Map<String, Object> listedTable, SQLException error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", listedTable.get("schema"));
        out.put("name", listedTable.get("name"));
        out.put("type", listedTable.get("type"));
        out.put("remarks", listedTable.get("remarks"));
        out.put("error", error.getMessage());
        return out;
    }
}
