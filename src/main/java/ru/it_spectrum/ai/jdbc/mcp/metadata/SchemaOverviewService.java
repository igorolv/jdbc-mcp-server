package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.context.ContextTable;
import ru.it_spectrum.ai.jdbc.mcp.model.context.ErrorTable;
import ru.it_spectrum.ai.jdbc.mcp.model.context.RelationshipEdge;
import ru.it_spectrum.ai.jdbc.mcp.model.context.SchemaOverview;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaOverviewService extends SchemaContextSupport {

    SchemaOverviewService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                          SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public SchemaOverview schemaOverview(String schema, String namePattern,
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

        List<ContextTable> tables = new ArrayList<>(selected.size());
        List<RelationshipEdge> relationships = new ArrayList<>();
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
            for (RelationshipEdge edge : outgoingEdges(described)) {
                addUnique(relationships, relationshipKeys, edge);
            }
        }
        decorateAndAppendObserved(relationships, describedNamesUpper, observed);

        return new SchemaOverview(schema, namePattern, views,
                Boolean.TRUE.equals(includeStats), observed,
                listed.size(), tables.size(), truncated, tables, relationships);
    }

    private ErrorTable errorTable(TableEntry listedTable, SQLException error) {
        return new ErrorTable(
                listedTable.schema(),
                listedTable.name(),
                listedTable.type(),
                listedTable.remarks(),
                error.getMessage());
    }
}
