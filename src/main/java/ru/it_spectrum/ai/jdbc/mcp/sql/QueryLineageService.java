package ru.it_spectrum.ai.jdbc.mcp.sql;

import net.sf.jsqlparser.JSQLParserException;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.LineageCycle;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.LineageDirectObject;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.LineageExpandedObject;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.LineageObjectRef;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.LineagePhysicalTable;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.LineageUnresolvedObject;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.LineageWarning;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.QueryLineageResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.RoutineEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryInspection;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryTableRef;
import ru.it_spectrum.ai.jdbc.mcp.usage.ExtractedSqlStatement;
import ru.it_spectrum.ai.jdbc.mcp.usage.ProceduralSqlExtractor;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata-aware SQL lineage resolver. {@link QueryAnalysisService} stays a pure AST inspector;
 * this service resolves parsed object names against live JDBC metadata and recursively expands
 * database views and, best-effort, routines.
 */
@Service
public class QueryLineageService {

    private static final String[] RELATION_TYPES = {"TABLE", "VIEW", "MATERIALIZED VIEW"};
    private static final int DEFAULT_MAX_DEPTH = 5;
    private static final int HARD_MAX_DEPTH = 20;
    private static final Pattern CREATE_VIEW_AS =
            Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\b.*?\\bAS\\b");

    private static final Set<String> BUILTIN_FUNCTIONS = Set.of(
            "ABS", "AVG", "CAST", "CEIL", "CEILING", "COALESCE", "CONCAT", "COUNT", "CURRENT_DATE",
            "CURRENT_TIMESTAMP", "DATEADD", "DATEDIFF", "EXTRACT", "FLOOR", "GREATEST", "LEAST",
            "LOWER", "LTRIM", "MAX", "MIN", "NULLIF", "NVL", "ROUND", "RTRIM", "SUBSTRING",
            "SUM", "TO_CHAR", "TO_DATE", "TO_NUMBER", "TRIM", "UPPER"
    );

    private final QueryAnalysisService analysis;
    private final MetadataService metadata;
    private final ProceduralSqlExtractor proceduralSqlExtractor;

    public QueryLineageService(QueryAnalysisService analysis, MetadataService metadata,
                               ProceduralSqlExtractor proceduralSqlExtractor) {
        this.analysis = analysis;
        this.metadata = metadata;
        this.proceduralSqlExtractor = proceduralSqlExtractor;
    }

    public QueryLineageResult resolve(String sql, String schema, Boolean expandViews,
                                      Boolean expandRoutines, Integer maxDepth) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql is required");
        }
        int depthLimit = clamp(maxDepth);
        boolean doViews = expandViews == null || expandViews;
        boolean doRoutines = expandRoutines == null || expandRoutines;

        QueryInspection inspection = analysis.inspect(sql);
        Acc acc = new Acc(inspection, depthLimit, doViews, doRoutines);
        QueryAnalysisService.QueryModel model;
        try {
            model = analysis.model(sql);
        } catch (JSQLParserException | RuntimeException e) {
            acc.warn("parser_error", "JSqlParser could not parse the SQL, so lineage expansion was skipped: "
                    + rootMessage(e));
            return acc.result();
        }

        processModel(model, schema, List.of(), 0, true, acc);
        return acc.result();
    }

    private void processModel(QueryAnalysisService.QueryModel model, String defaultSchema,
                              List<String> via, int depth, boolean topLevel, Acc acc) throws SQLException {
        for (QueryTableRef table : model.tables) {
            String rawName = clean(table.name());
            if (rawName == null) continue;
            if (model.cteNames.contains(rawName)) continue;

            ResolvedObject resolved = resolveRelation(table.schema(), rawName, defaultSchema);
            if (topLevel) {
                acc.directObjects.add(new LineageDirectObject(
                        resolved.schemaOr(table.schema()),
                        resolved.nameOr(rawName),
                        resolved.type(),
                        table.alias(),
                        table.source(),
                        resolved.status()));
            }
            if (resolved.unresolved()) {
                acc.unresolved.add(new LineageUnresolvedObject(
                        table.schema(), rawName, "relation", table.source(), resolved.status(),
                        resolved.candidates(), via));
                continue;
            }
            expandObject(resolved, append(via, resolved.key()), depth, acc);
        }

        if (!acc.expandRoutines) return;
        for (String function : model.functions) {
            if (isBuiltinFunction(function)) continue;
            ResolvedObject routine = resolveRoutine(function, defaultSchema);
            if (routine.unresolved()) {
                if (function != null && function.contains(".")) {
                    acc.unresolved.add(new LineageUnresolvedObject(
                            routine.schema(), routine.nameOr(function), "routine", "function",
                            routine.status(), routine.candidates(), via));
                }
                continue;
            }
            if (topLevel) {
                acc.directObjects.add(new LineageDirectObject(
                        routine.schema(), routine.name(), routine.type(),
                        null, "function", routine.status()));
            }
            expandObject(routine, append(via, routine.key()), depth, acc);
        }
    }

    private void expandObject(ResolvedObject object,
                              List<String> via, int depth, Acc acc) throws SQLException {
        if (object.isTable()) {
            acc.physicalTables.add(new LineagePhysicalTable(
                    object.schema(), object.name(), object.type(), tailVia(via), depth));
            return;
        }
        if (depth >= acc.maxDepth) {
            acc.warn("max_depth_reached", "Lineage expansion stopped at " + object.key()
                    + " because maxDepth=" + acc.maxDepth + " was reached.");
            return;
        }
        if (hasCycle(via)) {
            acc.cycles.add(new LineageCycle(via));
            return;
        }
        if (object.isView()) {
            if (!acc.expandViews) return;
            expandView(object, via, depth, acc);
            return;
        }
        if (object.isRoutine()) {
            if (!acc.expandRoutines) return;
            expandRoutine(object, via, depth, acc);
        }
    }

    private void expandView(ResolvedObject view,
                            List<String> via, int depth, Acc acc) throws SQLException {
        String definition = metadata.viewDefinition(view.schema(), view.name());
        if (definition == null || definition.isBlank()) {
            acc.unresolved.add(new LineageUnresolvedObject(
                    view.schema(), view.name(), "view", "definition", "definition_not_found",
                    List.of(), tailVia(via)));
            return;
        }
        QueryAnalysisService.QueryModel model;
        try {
            model = analysis.model(normalizeViewSql(definition));
        } catch (JSQLParserException | RuntimeException e) {
            acc.warn("view_parse_error", "Could not parse definition of " + view.key() + ": " + rootMessage(e));
            return;
        }
        List<LineageObjectRef> deps = dependencies(model, view.schema(), acc);
        acc.expandedObjects.add(new LineageExpandedObject(
                view.schema(), view.name(), view.type(), deps, tailVia(via), depth,
                "view_definition"));
        processModel(model, view.schema(), via, depth + 1, false, acc);
    }

    private void expandRoutine(ResolvedObject routine,
                               List<String> via, int depth, Acc acc) throws SQLException {
        String source = metadata.routineSource(routine.schema(), routine.name());
        if (source == null || source.isBlank()) {
            acc.unresolved.add(new LineageUnresolvedObject(
                    routine.schema(), routine.name(), "routine", "definition", "definition_not_found",
                    List.of(), tailVia(via)));
            return;
        }
        List<ExtractedSqlStatement> statements = proceduralSqlExtractor.extract(source).stream()
                .filter(s -> "SELECT".equalsIgnoreCase(s.kind()) || "WITH".equalsIgnoreCase(s.kind()))
                .toList();
        if (statements.isEmpty()) {
            acc.warn("routine_no_select_extracted", "No SELECT/WITH statements were extracted from "
                    + routine.key() + "; routine lineage may be incomplete.");
            acc.expandedObjects.add(new LineageExpandedObject(
                    routine.schema(), routine.name(), routine.type(), List.of(), tailVia(via), depth,
                    "routine_body_no_select"));
            return;
        }
        List<LineageObjectRef> deps = new ArrayList<>();
        for (ExtractedSqlStatement statement : statements) {
            try {
                QueryAnalysisService.QueryModel model = analysis.model(statement.sql());
                deps.addAll(dependencies(model, routine.schema(), acc));
                processModel(model, routine.schema(), via, depth + 1, false, acc);
            } catch (JSQLParserException | RuntimeException e) {
                acc.warn("routine_statement_parse_error", "Could not parse statement "
                        + statement.ordinal() + " from " + routine.key() + ": " + rootMessage(e));
            }
        }
        acc.expandedObjects.add(new LineageExpandedObject(
                routine.schema(), routine.name(), routine.type(), dedupeRefs(deps),
                tailVia(via), depth, "routine_body_extracted_sql"));
    }

    private List<LineageObjectRef> dependencies(QueryAnalysisService.QueryModel model, String defaultSchema, Acc acc)
            throws SQLException {
        List<LineageObjectRef> deps = new ArrayList<>();
        for (QueryTableRef table : model.tables) {
            String rawName = clean(table.name());
            if (rawName == null || model.cteNames.contains(rawName)) continue;
            ResolvedObject resolved = resolveRelation(table.schema(), rawName, defaultSchema);
            if (resolved.unresolved()) {
                acc.unresolved.add(new LineageUnresolvedObject(
                        table.schema(), rawName, "relation", table.source(), resolved.status(),
                        resolved.candidates(), List.of()));
                continue;
            }
            deps.add(resolved.ref());
        }
        if (acc.expandRoutines) {
            for (String function : model.functions) {
                if (isBuiltinFunction(function)) continue;
                ResolvedObject routine = resolveRoutine(function, defaultSchema);
                if (!routine.unresolved()) deps.add(routine.ref());
            }
        }
        return dedupeRefs(deps);
    }

    private ResolvedObject resolveRelation(String schema, String name, String defaultSchema) throws SQLException {
        String cleanSchema = clean(schema);
        if (cleanSchema == null) cleanSchema = clean(defaultSchema);
        String cleanName = clean(name);
        List<TableEntry> matches = new ArrayList<>();
        if (cleanSchema != null) {
            matches.addAll(listTables(cleanSchema, cleanName));
        } else {
            matches.addAll(listTables(null, cleanName));
            if (matches.isEmpty()) {
                matches.addAll(metadata.findTablesByName(cleanName));
            }
        }
        matches = new ArrayList<>(dedupeTables(matches));
        if (matches.isEmpty() && cleanName != null && !cleanName.equals(cleanName.toUpperCase(Locale.ROOT))) {
            if (cleanSchema != null) {
                matches.addAll(listTables(cleanSchema, cleanName.toUpperCase(Locale.ROOT)));
            } else {
                matches.addAll(listTables(null, cleanName.toUpperCase(Locale.ROOT)));
                if (matches.isEmpty()) {
                    matches.addAll(metadata.findTablesByName(cleanName.toUpperCase(Locale.ROOT)));
                }
            }
            matches = new ArrayList<>(dedupeTables(matches));
        }
        if (matches.isEmpty()) {
            return ResolvedObject.unresolved(cleanSchema, cleanName, null, "not_found", List.of());
        }
        List<TableEntry> exact = matches.stream()
                .filter(t -> equalsName(t.name(), cleanName))
                .toList();
        List<TableEntry> candidates = exact.isEmpty() ? matches : exact;
        if (candidates.size() > 1) {
            return ResolvedObject.unresolved(cleanSchema, cleanName, null, "ambiguous",
                    candidates.stream().map(QueryLineageService::ref).toList());
        }
        TableEntry row = candidates.getFirst();
        return ResolvedObject.resolved(row.schema(), row.name(), normalizeType(row.type()), "resolved");
    }

    private ResolvedObject resolveRoutine(String function, String defaultSchema) throws SQLException {
        NameParts parts = splitQualified(function);
        if (parts.name() == null) {
            return ResolvedObject.unresolved(parts.schema(), null, "ROUTINE", "not_found", List.of());
        }
        String schema = parts.schema() == null ? defaultSchema : parts.schema();
        List<RoutineEntry> matches = listRoutines(schema, parts.name());
        if (matches.isEmpty() && !parts.name().equals(parts.name().toUpperCase(Locale.ROOT))) {
            matches = listRoutines(schema, parts.name().toUpperCase(Locale.ROOT));
        }
        List<RoutineEntry> exact = matches.stream()
                .filter(r -> equalsName(r.name(), parts.name()))
                .toList();
        List<RoutineEntry> candidates = exact.isEmpty() ? matches : exact;
        if (candidates.isEmpty()) {
            return ResolvedObject.unresolved(schema, parts.name(), "ROUTINE", "not_found", List.of());
        }
        if (candidates.size() > 1) {
            return ResolvedObject.unresolved(schema, parts.name(), "ROUTINE", "ambiguous",
                    candidates.stream().map(r -> new LineageObjectRef(r.schema(), r.name(), r.type())).toList());
        }
        RoutineEntry routine = candidates.getFirst();
        return ResolvedObject.resolved(routine.schema(), routine.name(), routine.type(), "resolved");
    }

    private List<TableEntry> listTables(String schema, String name) throws SQLException {
        return metadata.listTables(schema, name, RELATION_TYPES);
    }

    private List<RoutineEntry> listRoutines(String schema, String namePattern) throws SQLException {
        return metadata.listRoutines(schema, namePattern);
    }

    private static String normalizeViewSql(String definition) {
        String trimmed = definition == null ? "" : definition.trim();
        Matcher createView = CREATE_VIEW_AS.matcher(trimmed);
        if (createView.find()) {
            return trimmed.substring(createView.end()).trim();
        }
        return trimmed;
    }

    private static List<TableEntry> dedupeTables(Collection<TableEntry> rows) {
        Map<String, TableEntry> byKey = new LinkedHashMap<>();
        for (TableEntry row : rows) {
            if (row == null || row.name() == null) continue;
            byKey.putIfAbsent(key(row.schema(), row.name()), row);
        }
        return List.copyOf(byKey.values());
    }

    private static List<LineageObjectRef> dedupeRefs(Collection<LineageObjectRef> rows) {
        Map<String, LineageObjectRef> byKey = new LinkedHashMap<>();
        for (LineageObjectRef row : rows) {
            if (row == null || row.name() == null) continue;
            byKey.putIfAbsent(key(row.schema(), row.name()) + "|" + row.type(), row);
        }
        return List.copyOf(byKey.values());
    }

    private static boolean hasCycle(List<String> via) {
        Set<String> seen = new LinkedHashSet<>();
        for (String item : via) {
            if (!seen.add(item)) return true;
        }
        return false;
    }

    private static List<String> append(List<String> path, String item) {
        List<String> out = new ArrayList<>(path);
        out.add(item);
        return List.copyOf(out);
    }

    private static List<String> tailVia(List<String> via) {
        if (via == null || via.size() <= 1) return List.of();
        return List.copyOf(via.subList(0, via.size() - 1));
    }

    private static int clamp(Integer maxDepth) {
        if (maxDepth == null) return DEFAULT_MAX_DEPTH;
        return Math.clamp(maxDepth, 0, HARD_MAX_DEPTH);
    }

    private static LineageObjectRef ref(TableEntry row) {
        return new LineageObjectRef(row.schema(), row.name(), normalizeType(row.type()));
    }

    private static String key(String schema, String name) {
        return (schema == null ? "" : schema.toUpperCase(Locale.ROOT))
                + "." + (name == null ? "" : name.toUpperCase(Locale.ROOT));
    }

    private static boolean equalsName(String a, String b) {
        return clean(a) != null && clean(a).equalsIgnoreCase(clean(b));
    }

    private static String clean(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (s.isBlank()) return null;
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("[") && s.endsWith("]"))
                || (s.startsWith("`") && s.endsWith("`")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String normalizeType(String type) {
        return type == null || type.isBlank() ? null : type.toUpperCase(Locale.ROOT);
    }

    private static boolean isBuiltinFunction(String function) {
        String name = splitQualified(function).name();
        return name == null || BUILTIN_FUNCTIONS.contains(name.toUpperCase(Locale.ROOT));
    }

    private static NameParts splitQualified(String raw) {
        String value = clean(raw);
        if (value == null) return new NameParts(null, null);
        int dot = value.lastIndexOf('.');
        if (dot < 0) return new NameParts(null, value);
        return new NameParts(clean(value.substring(0, dot)), clean(value.substring(dot + 1)));
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }

    private record NameParts(String schema, String name) {
    }
    private record ResolvedObject(String schema, String name, String type, String status,
                                  List<LineageObjectRef> candidates) {
        static ResolvedObject resolved(String schema, String name, String type, String status) {
            return new ResolvedObject(schema, name, type, status, List.of());
        }

        static ResolvedObject unresolved(String schema, String name, String type, String status,
                                         List<LineageObjectRef> candidates) {
            return new ResolvedObject(schema, name, type, status, candidates == null ? List.of() : candidates);
        }

        boolean unresolved() {
            return !"resolved".equals(status);
        }

        String schemaOr(String fallback) {
            return schema == null ? fallback : schema;
        }

        String nameOr(String fallback) {
            return name == null ? fallback : name;
        }

        String key() {
            return QueryLineageService.key(schema, name);
        }

        LineageObjectRef ref() {
            return new LineageObjectRef(schema, name, type);
        }

        boolean isTable() {
            return "TABLE".equals(type);
        }

        boolean isView() {
            return type != null && type.contains("VIEW");
        }

        boolean isRoutine() {
            return type != null && (type.contains("FUNCTION") || type.contains("PROCEDURE")
                    || type.contains("ROUTINE") || type.contains("PACKAGE"));
        }
    }

    private static final class Acc {
        final QueryInspection inspection;
        final int maxDepth;
        final boolean expandViews;
        final boolean expandRoutines;
        final List<LineageDirectObject> directObjects = new ArrayList<>();
        final List<LineagePhysicalTable> physicalTables = new ArrayList<>();
        final List<LineageExpandedObject> expandedObjects = new ArrayList<>();
        final List<LineageUnresolvedObject> unresolved = new ArrayList<>();
        final List<LineageCycle> cycles = new ArrayList<>();
        final List<LineageWarning> warnings = new ArrayList<>();

        Acc(QueryInspection inspection, int maxDepth, boolean expandViews, boolean expandRoutines) {
            this.inspection = inspection;
            this.maxDepth = maxDepth;
            this.expandViews = expandViews;
            this.expandRoutines = expandRoutines;
        }

        void warn(String code, String message) {
            warnings.add(new LineageWarning(code, message));
        }

        QueryLineageResult result() {
            return new QueryLineageResult(
                    ru.it_spectrum.ai.jdbc.mcp.model.Opaque.of(inspection),
                    List.copyOf(directObjects),
                    dedupePhysical(physicalTables),
                    List.copyOf(expandedObjects),
                    List.copyOf(unresolved),
                    List.copyOf(cycles),
                    List.copyOf(warnings),
                    maxDepth);
        }

        private static List<LineagePhysicalTable> dedupePhysical(List<LineagePhysicalTable> rows) {
            Map<String, LineagePhysicalTable> byKey = new LinkedHashMap<>();
            for (LineagePhysicalTable row : rows) {
                String via = String.join(">", row.via());
                byKey.putIfAbsent(key(row.schema(), row.name()) + "|" + via, row);
            }
            return List.copyOf(byKey.values());
        }
    }
}
