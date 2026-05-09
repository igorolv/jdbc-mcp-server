package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.context.JoinPathStep;
import ru.it_spectrum.ai.jdbc.mcp.model.context.FindJoinPaths;
import ru.it_spectrum.ai.jdbc.mcp.model.context.RelationshipEdge;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaJoinPathService extends SchemaContextSupport {

    SchemaJoinPathService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                          SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public FindJoinPaths findJoinPaths(String fromSchema, String fromTable,
                                             String toSchema, String toTable,
                                             Integer maxDepth, Integer maxPaths,
                                             Integer scanLimit, Boolean includeObserved) throws SQLException {
        if (fromTable == null || fromTable.isBlank()) {
            throw new IllegalArgumentException("fromTable must be provided");
        }
        if (toTable == null || toTable.isBlank()) {
            throw new IllegalArgumentException("toTable must be provided");
        }
        int depthLimit = clamp(maxDepth, MAX_DEPTH, 1, MAX_DEPTH);
        int pathLimit = clamp(maxPaths, DEFAULT_MAX_PATHS, 1, MAX_PATHS_LIMIT);
        int tableLimit = clamp(scanLimit, MAX_TABLES_LIMIT, 1, MAX_TABLES_LIMIT);
        boolean observed = defaultIncludeObserved(includeObserved);

        TableDescription fromInfo = metadata.describeTable(fromSchema, fromTable);
        String effectiveFromSchema = fromInfo.schema();
        String effectiveFromTable = fromInfo.name();
        TableDescription toInfo = metadata.describeTable(toSchema, toTable);
        String effectiveToSchema = toInfo.schema();
        String effectiveToTable = toInfo.name();

        Map<String, TableDescription> described = loadSchemaTables(effectiveFromSchema, tableLimit);
        described.putIfAbsent(key(effectiveFromSchema, effectiveFromTable), fromInfo);
        described.putIfAbsent(key(effectiveToSchema, effectiveToTable), toInfo);

        List<RelationshipEdge> rawEdges = new ArrayList<>();
        for (TableDescription info : described.values()) {
            rawEdges.addAll(outgoingEdges(info));
        }
        Set<String> describedNamesUpper = new HashSet<>();
        for (TableDescription info : described.values()) {
            describedNamesUpper.add(upper(info.name()));
        }
        decorateAndAppendObserved(rawEdges, describedNamesUpper, observed);

        List<GraphEdge> graphEdges = new ArrayList<>();
        for (RelationshipEdge edge : rawEdges) {
            graphEdges.add(GraphEdge.forward(edge));
            graphEdges.add(GraphEdge.reverse(edge));
        }

        Map<String, List<GraphEdge>> byFrom = new HashMap<>();
        for (GraphEdge edge : graphEdges) {
            byFrom.computeIfAbsent(key(edge.fromSchema(), edge.fromTable()), ignored -> new ArrayList<>()).add(edge);
        }

        String start = key(effectiveFromSchema, effectiveFromTable);
        String target = key(effectiveToSchema, effectiveToTable);
        List<List<JoinPathStep>> paths = searchPaths(start, target, byFrom, depthLimit, pathLimit);

        return new FindJoinPaths(effectiveFromSchema, effectiveFromTable,
                effectiveToSchema, effectiveToTable,
                depthLimit, observed, described.size(), paths.size(), paths);
    }
}
