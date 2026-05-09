package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.context.RelationshipEdge;
import ru.it_spectrum.ai.jdbc.mcp.model.context.TableContext;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaTableContextService extends SchemaContextSupport {

    SchemaTableContextService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                              SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public TableContext tableContext(String schema, String table, Integer depth,
                                            Boolean includeIncoming, Boolean includeStats,
                                            Boolean includeObserved)
            throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must be provided");
        }
        int maxDepth = clamp(depth, DEFAULT_DEPTH, 0, MAX_DEPTH);
        boolean incoming = includeIncoming == null || includeIncoming;
        boolean observed = defaultIncludeObserved(includeObserved);

        TableDescription root = metadata.describeTable(schema, table);
        String rootSchema = root.schema();
        String rootTable = root.name();

        Map<String, TableDescription> described = new LinkedHashMap<>();
        Queue<NodeDepth> queue = new ArrayDeque<>();
        described.put(key(rootSchema, rootTable), root);
        queue.add(new NodeDepth(rootSchema, rootTable, 0));

        while (!queue.isEmpty()) {
            NodeDepth current = queue.remove();
            if (current.depth() >= maxDepth) continue;
            TableDescription currentInfo = described.get(key(current.schema(), current.table()));
            if (currentInfo == null) continue;

            for (Neighbor neighbor : neighbors(currentInfo, incoming)) {
                String neighborKey = key(neighbor.schema(), neighbor.table());
                if (described.containsKey(neighborKey)) continue;
                TableDescription neighborInfo = metadata.describeTable(neighbor.schema(), neighbor.table());
                described.put(neighborKey, neighborInfo);
                queue.add(new NodeDepth(neighbor.schema(), neighbor.table(), current.depth() + 1));
            }
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        List<RelationshipEdge> relationships = new ArrayList<>();
        Set<String> relationshipKeys = new HashSet<>();
        for (TableDescription info : described.values()) {
            Map<String, Object> tableContext = compactTable(info, Boolean.TRUE.equals(includeStats));
            if (observed && usageCatalog != null && usageCatalog.enabled()) {
                tableContext.put("evidence", usageCatalog.tableEvidenceProfile(
                        info.schema(), info.name()).toMap());
            }
            tables.add(tableContext);
            for (RelationshipEdge edge : outgoingEdges(info)) {
                addUnique(relationships, relationshipKeys, edge);
            }
            if (incoming) {
                for (RelationshipEdge edge : incomingEdges(info)) {
                    addUnique(relationships, relationshipKeys, edge);
                }
            }
        }
        Set<String> describedNamesUpper = new HashSet<>();
        for (TableDescription info : described.values()) {
            describedNamesUpper.add(upper(info.name()));
        }
        decorateAndAppendObserved(relationships, describedNamesUpper, observed);

        return new TableContext(rootSchema, rootTable, maxDepth, incoming,
                Boolean.TRUE.equals(includeStats), observed, tables, relationships);
    }
}
