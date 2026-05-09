package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Trigger;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
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
        if (!properties.catalogEnabled() || !properties.nativeCatalogEnabled()) {
            return List.of();
        }
        List<QueryUsage> out = new ArrayList<>();
        for (String schema : schemas()) {
            if (properties.nativeIncludeViews()) {
                addViews(schema, out);
            }
            if (properties.nativeIncludeRoutines()) {
                addRoutines(schema, out);
            }
            if (properties.nativeIncludeTriggers()) {
                addTriggers(schema, out);
            }
        }
        return List.copyOf(out);
    }

    private List<String> schemas() throws Exception {
        List<String> configured = properties.resolvedNativeSchemas();
        if (!configured.isEmpty()) return configured;
        String defaultSchema = metadata.defaultSchema();
        return defaultSchema == null || defaultSchema.isBlank() ? List.of() : List.of(defaultSchema);
    }

    private void addViews(String schema, List<QueryUsage> out) throws Exception {
        for (TableEntry view : metadata.listTables(schema, "%", new String[]{"VIEW", "MATERIALIZED VIEW"})) {
            if (limitReached(out)) return;
            String definition = metadata.viewDefinition(view.schema(), view.name());
            if (definition == null || definition.isBlank()) continue;
            String sql = normalizeViewSql(definition);
            String type = view.type() == null ? "VIEW" : view.type();
            String sourceKind = type.toUpperCase(Locale.ROOT).contains("MATERIALIZED")
                    ? "database-materialized-view" : "database-view";
            out.add(new QueryUsage(
                    properties.effectiveDataSourceId(),
                    new QueryUsageSource(sourceKind, path("view", view.schema(), view.name()), null),
                    "Database " + type.toLowerCase(Locale.ROOT) + " " + qualified(view.schema(), view.name()),
                    null,
                    List.of("database-native", "view"),
                    sql,
                    null,
                    null,
                    null,
                    meta("VIEW", view.schema(), view.name(), null, null)
            ));
        }
    }

    private void addRoutines(String schema, List<QueryUsage> out) throws Exception {
        QueryResult routines = metadata.listRoutines(schema, "%");
        for (Map<String, Object> row : routines.rows()) {
            if (limitReached(out)) return;
            String objectSchema = stringValue(getCI(row, "schema"));
            String name = stringValue(getCI(row, "name"));
            String kind = stringValue(getCI(row, "kind"));
            if (name == null) continue;
            String source = metadata.routineSource(objectSchema, name);
            if (source == null || source.isBlank()) continue;
            String sourceKind = "database-" + normalizeKind(kind, "routine");
            List<ExtractedSqlStatement> statements = proceduralSqlExtractor.extract(source);
            if (statements.isEmpty()) {
                out.add(new QueryUsage(
                        properties.effectiveDataSourceId(),
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
            for (ExtractedSqlStatement statement : statements) {
                if (limitReached(out)) return;
                out.add(new QueryUsage(
                        properties.effectiveDataSourceId(),
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
        }
    }

    private void addTriggers(String schema, List<QueryUsage> out) throws Exception {
        for (TableEntry table : metadata.listTables(schema, "%", new String[]{"TABLE"})) {
            if (limitReached(out)) return;
            for (Trigger trigger : metadata.tableTriggers(table.schema(), table.name(), true)) {
                if (limitReached(out)) return;
                String definition = trigger.definition();
                if (definition == null || definition.isBlank()) continue;
                List<ExtractedSqlStatement> statements = proceduralSqlExtractor.extract(definition);
                if (statements.isEmpty()) {
                    out.add(triggerUsage(table, trigger, definition, null, null, null));
                    continue;
                }
                for (ExtractedSqlStatement statement : statements) {
                    if (limitReached(out)) return;
                    out.add(triggerUsage(table, trigger, statement.sql(), "stmt" + statement.ordinal(),
                            statement.ordinal(), statement.kind()));
                }
            }
        }
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
                properties.effectiveDataSourceId(),
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
