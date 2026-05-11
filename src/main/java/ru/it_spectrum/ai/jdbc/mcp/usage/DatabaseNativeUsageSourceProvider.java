package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.RoutineEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Trigger;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives usage records from objects that live in the connected database itself.
 *
 * <p>Views usually expose a parseable SELECT body and therefore contribute table, column and join
 * evidence immediately. Routine and trigger bodies are less portable across engines, so this
 * provider delegates to an ANTLR-based pre-extractor and otherwise keeps the object as a provenance
 * record.
 */
@Service
public class DatabaseNativeUsageSourceProvider implements UsageCatalogSource {

private static final Logger log = LoggerFactory.getLogger(DatabaseNativeUsageSourceProvider.class);

    private static final Pattern CREATE_VIEW_AS =
            Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\b.*?\\bAS\\b");

    private final UsageProperties properties;
    private final MetadataService metadata;
    private final ProceduralSqlExtractor proceduralSqlExtractor;

    public DatabaseNativeUsageSourceProvider(UsageProperties properties, MetadataService metadata) {
        this(properties, metadata, new ProceduralSqlExtractor());
    }

    @Autowired
    public DatabaseNativeUsageSourceProvider(UsageProperties properties, MetadataService metadata,
                                             ProceduralSqlExtractor proceduralSqlExtractor) {
        this.properties = properties;
        this.metadata = metadata;
        this.proceduralSqlExtractor = proceduralSqlExtractor;
    }

    @Override
    public String name() {
        return "database-native";
    }

    @Override
    public List<QueryUsage> load() throws Exception {
        if (!properties.catalogEnabled()) {
            return List.of();
        }
        long phaseStart = System.currentTimeMillis();
        List<QueryUsage> out = new ArrayList<>();
        List<String> schemas = schemas();
        log.info("DatabaseNative: loading from schemas {}...", schemas);
        for (String schema : schemas) {
            if (properties.nativeIncludeViews()) {
                long t = System.currentTimeMillis();
                int before = out.size();
                addViews(schema, out);
                log.info("DatabaseNative: {} views loaded from {} in {} ms (total records: {})",
                        out.size() - before, schema, System.currentTimeMillis() - t, out.size());
            }
            if (properties.nativeIncludeRoutines()) {
                long t = System.currentTimeMillis();
                int before = out.size();
                addRoutines(schema, out);
                log.info("DatabaseNative: {} routines loaded from {} in {} ms (total records: {})",
                        out.size() - before, schema, System.currentTimeMillis() - t, out.size());
            }
            if (properties.nativeIncludeTriggers()) {
                long t = System.currentTimeMillis();
                int before = out.size();
                addTriggers(schema, out);
                log.info("DatabaseNative: {} triggers loaded from {} in {} ms (total records: {})",
                        out.size() - before, schema, System.currentTimeMillis() - t, out.size());
            }
        }
        log.info("DatabaseNative: total {} records loaded from {} schemas in {} ms",
                out.size(), schemas.size(), System.currentTimeMillis() - phaseStart);
        return List.copyOf(out);
    }

    private List<String> schemas() throws Exception {
        List<String> configured = properties.resolvedNativeSchemas();
        if (!configured.isEmpty()) return configured;
        String defaultSchema = metadata.defaultSchema();
        return defaultSchema == null || defaultSchema.isBlank() ? List.of() : List.of(defaultSchema);
    }

    private void addViews(String schema, List<QueryUsage> out) throws Exception {
        long t = System.currentTimeMillis();
        List<Map<String, Object>> viewRows = metadata.schemaViewDefinitions(schema);
        int count = 0;
        for (Map<String, Object> row : viewRows) {
            if (limitReached(out)) return;
            String objectSchema = stringValue(getCI(row, "schema"));
            String name = stringValue(getCI(row, "name"));
            String definition = stringValue(getCI(row, "definition"));
            if (definition == null || definition.isBlank()) continue;
            String sql = normalizeViewSql(definition);
            String type = "VIEW";
            String sourceKind = "database-view";
            out.add(new QueryUsage(
                    new QueryUsageSource(sourceKind, path("view", objectSchema, name), null),
                    "Database " + type.toLowerCase(Locale.ROOT) + " " + qualified(objectSchema, name),
                    null,
                    List.of("database-native", "view"),
                    sql,
                    null,
                    null,
                    null,
                    meta("VIEW", objectSchema, name, null, null)
            ));
            count++;
        }
        log.info("DatabaseNative:   {} views found in {}, {} added in {} ms",
                viewRows.size(), schema, count, System.currentTimeMillis() - t);
    }

    private void addRoutines(String schema, List<QueryUsage> out) throws Exception {
        List<RoutineEntry> routines = metadata.listRoutines(schema, "%");
        int totalRoutines = routines.size();
        log.info("DatabaseNative: processing {} routines/sequences in {}...", totalRoutines, schema);
        int skippedNoSource = 0;
        int extracted = 0;
        for (RoutineEntry row : routines) {
            if (limitReached(out)) return;
            String objectSchema = row.schema();
            String name = row.name();
            String kind = row.type();
            if (name == null) continue;
            long t = System.currentTimeMillis();
            String source = metadata.routineSource(objectSchema, name);
            if (source == null || source.isBlank()) {
                skippedNoSource++;
                continue;
            }
            String sourceKind = "database-" + normalizeKind(kind, "routine");
            if ("PACKAGE".equalsIgnoreCase(kind)) {
                int before = out.size();
                addPackageRoutines(out, objectSchema, name, kind, sourceKind, source);
                log.info("DatabaseNative:   package {} extracted {} stmts in {} ms",
                        qualified(objectSchema, name), out.size() - before, System.currentTimeMillis() - t);
                continue;
            }
            List<ExtractedSqlStatement> statements = proceduralSqlExtractor.extract(source);
            if (statements.isEmpty()) {
                out.add(new QueryUsage(
                        new QueryUsageSource(sourceKind, path("routine", objectSchema, name), null),
                        "Database " + normalizeKind(kind, "routine") + " " + qualified(objectSchema, name),
                        null,
                        List.of("database-native", "routine"),
                        source,
                        null,
                        null,
                        null,
                        meta(kind, objectSchema, name, null, null)
                ));
                continue;
            }
            extracted++;
            for (ExtractedSqlStatement statement : statements) {
                if (limitReached(out)) return;
out.add(new QueryUsage(
                        new QueryUsageSource(sourceKind, path("routine", objectSchema, name),
                                "stmt" + statement.ordinal()),
                        "Database " + normalizeKind(kind, "routine") + " " + qualified(objectSchema, name),
                        null,
                        List.of("database-native", "routine"),
                        statement.sql(),
                        null,
                        null,
                        null,
                        meta(kind, objectSchema, name, null, Map.of(
                                "statementOrdinal", statement.ordinal(),
                                "statementKind", statement.kind()))
                ));
            }
            long elapsed = System.currentTimeMillis() - t;
            if (elapsed > 100) {
                log.info("DatabaseNative:   routine {} ({}) took {} ms, extracted {} stmts",
                        qualified(objectSchema, name), kind, elapsed, statements.size());
            }
        }
        log.info("DatabaseNative: routines done for {}: {} records, {} skipped (no source), {} with extracted stmts",
                schema, totalRoutines, skippedNoSource, extracted);
    }

    private void addPackageRoutines(List<QueryUsage> out, String objectSchema, String packageName,
                                    String kind, String sourceKind, String source) {
        List<ExtractedRoutineSqlStatement> statements =
                proceduralSqlExtractor.extractOraclePackageBody(source);
        if (statements.isEmpty()) {
            out.add(new QueryUsage(
                    new QueryUsageSource(sourceKind, path("package", objectSchema, packageName), null),
                    "Database package " + qualified(objectSchema, packageName),
                    null,
                    List.of("database-native", "routine", "package"),
                    source,
                    null,
                    null,
                    null,
                    meta(kind, objectSchema, packageName, null, null)
            ));
            return;
        }
        for (ExtractedRoutineSqlStatement statement : statements) {
            if (limitReached(out)) return;
            String unit = safe(statement.routineName()) + ".stmt" + statement.ordinal();
            out.add(new QueryUsage(
                    new QueryUsageSource(sourceKind, path("package", objectSchema, packageName), unit),
                    "Database package " + qualified(objectSchema, packageName)
                            + "." + statement.routineName(),
                    null,
                    List.of("database-native", "routine", "package"),
                    statement.sql(),
                    null,
                    null,
                    null,
                    meta("PACKAGE BODY", objectSchema, packageName, null, Map.of(
                            "packageName", packageName,
                            "subprogramName", statement.routineName(),
                            "subprogramKind", statement.routineKind(),
                            "statementOrdinal", statement.ordinal(),
                            "statementKind", statement.kind()))
            ));
        }
    }

    private void addTriggers(String schema, List<QueryUsage> out) throws Exception {
        long t = System.currentTimeMillis();
        List<Trigger> allTriggers = metadata.schemaTriggers(schema, true);
        log.info("DatabaseNative: processing {} triggers for schema {}...", allTriggers.size(), schema);
        int triggerCount = 0;
        for (Trigger trigger : allTriggers) {
            if (limitReached(out)) return;
            String definition = trigger.definition();
            if (definition == null || definition.isBlank()) continue;
            TableEntry table = new TableEntry(trigger.schema(), trigger.table(), "TABLE");
            List<ExtractedSqlStatement> statements = proceduralSqlExtractor.extract(definition);
            if (statements.isEmpty()) {
                out.add(triggerUsage(table, trigger, definition, null, null, null));
                triggerCount++;
                continue;
            }
            for (ExtractedSqlStatement statement : statements) {
                if (limitReached(out)) return;
                out.add(triggerUsage(table, trigger, statement.sql(), "stmt" + statement.ordinal(),
                        statement.ordinal(), statement.kind()));
                triggerCount++;
            }
        }
        log.info("DatabaseNative: triggers done for {}: {} triggers in {} ms",
                schema, triggerCount, System.currentTimeMillis() - t);
    }

    private QueryUsage triggerUsage(TableEntry table, Trigger trigger, String sql,
                                    String unit, Integer statementOrdinal, String statementKind) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("table", table.name());
        extra.put("timing", trigger.timing());
        extra.put("events", trigger.events());
        extra.put("enabled", trigger.enabled());
        if (statementOrdinal != null) {
            extra.put("statementOrdinal", statementOrdinal);
        }
        if (statementKind != null) {
            extra.put("statementKind", statementKind);
        }
        return new QueryUsage(
                new QueryUsageSource("database-trigger",
                        path("trigger", table.schema(), table.name() + "." + trigger.name()), unit),
                "Database trigger " + qualified(table.schema(), trigger.name()),
                null,
                List.of("database-native", "trigger"),
                sql,
                null,
                null,
                null,
                meta("TRIGGER", table.schema(), trigger.name(), null, extra)
        );
    }

    private boolean limitReached(List<QueryUsage> out) {
        return out.size() >= properties.nativeMaxObjects();
    }

    private static String normalizeViewSql(String definition) {
        String trimmed = definition == null ? "" : definition.trim();
        Matcher createView = CREATE_VIEW_AS.matcher(trimmed);
        if (createView.find()) {
            return trimmed.substring(createView.end()).trim();
        }
        return trimmed;
    }

    private static String path(String type, String schema, String name) {
        return "native/" + type + "/" + safe(schema) + "." + safe(name);
    }

    private static String qualified(String schema, String name) {
        return (schema == null || schema.isBlank()) ? name : schema + "." + name;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "_";
        return value.replace('#', '_');
    }

    private static String normalizeKind(String kind, String fallback) {
        if (kind == null || kind.isBlank()) return fallback;
        String normalized = kind.toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        return normalized.endsWith("s") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static Map<String, Object> meta(String objectType, String schema, String name,
                                            String language, Map<String, ?> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("native", true);
        out.put("objectType", objectType);
        out.put("schema", schema);
        out.put("objectName", name);
        if (language != null && !language.isBlank()) {
            out.put("language", language);
        }
        if (extra != null) {
            out.putAll(extra);
        }
        return out;
    }

    private static Object getCI(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value != null) return value;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
