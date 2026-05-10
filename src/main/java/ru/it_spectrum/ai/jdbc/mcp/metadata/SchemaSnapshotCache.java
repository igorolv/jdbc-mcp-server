package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.snapshot.CachedSchemaEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.snapshot.CachedTableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.snapshot.ListCacheEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.snapshot.SchemaSnapshot;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory snapshot cache for structural metadata. Caches the outputs of
 * {@code MetadataService.describeTable(schema, table)} and
 * {@code MetadataService.listTables(schema, namePattern, types)} with a TTL.
 *
 * <p>The cache is intentionally limited to <em>structural</em> metadata: tables, columns, keys,
 * indexes, FKs, constraints, triggers. It deliberately does not cache statistics, plans, samples,
 * distributions or anything that depends on live row counts — those are computed on demand by
 * {@link StatsService} and friends.
 *
 * <p>Thread-safety: backed by a {@link ConcurrentHashMap}; loaders are invoked outside any lock,
 * so two concurrent misses on the same key may both run the loader and the last writer wins —
 * acceptable for read-only metadata.
 */
@Service
public class SchemaSnapshotCache {

    @FunctionalInterface
    public interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private record TableKey(String schema, String table) {
        static TableKey of(String schema, String table) {
            return new TableKey(normalize(schema), normalize(table));
        }
    }

    private record ListKey(String schema, String pattern, String typesSig) {
        static ListKey of(String schema, String pattern, String[] types) {
            String sig;
            if (types == null || types.length == 0) sig = "*";
            else {
                String[] copy = Arrays.copyOf(types, types.length);
                Arrays.sort(copy, String.CASE_INSENSITIVE_ORDER);
                sig = String.join(",", copy).toLowerCase(Locale.ROOT);
            }
            return new ListKey(normalize(schema), normalize(pattern == null ? "%" : pattern), sig);
        }
    }

    private record Entry<T>(T value, long loadedAtMs) {}

    private final long ttlMs;
    private final int maxEntries;
    private final Map<TableKey, Entry<TableDescription>> describes = new ConcurrentHashMap<>();
    private final Map<ListKey, Entry<List<TableEntry>>> lists = new ConcurrentHashMap<>();
    private final AtomicLong describeHits = new AtomicLong();
    private final AtomicLong describeMisses = new AtomicLong();
    private final AtomicLong listHits = new AtomicLong();
    private final AtomicLong listMisses = new AtomicLong();

    public SchemaSnapshotCache(JdbcProperties properties) {
        this.ttlMs = Math.max(0L, properties.metadataCacheTtlSeconds()) * 1000L;
        this.maxEntries = Math.max(0, properties.metadataCacheMaxEntries());
    }

    public boolean enabled() {
        return ttlMs > 0;
    }

    public long ttlMs() {
        return ttlMs;
    }

    public TableDescription describeTable(String schema, String table,
                                          SqlSupplier<TableDescription> loader) throws SQLException {
        if (!enabled()) return loader.get();
        TableKey key = TableKey.of(schema, table);
        long now = System.currentTimeMillis();
        Entry<TableDescription> cached = describes.get(key);
        if (cached != null && now - cached.loadedAtMs < ttlMs) {
            describeHits.incrementAndGet();
            return cached.value;
        }
        describeMisses.incrementAndGet();
        TableDescription value = loader.get();
        if (maxEntries > 0 && describes.size() >= maxEntries) describes.clear();
        describes.put(key, new Entry<>(value, System.currentTimeMillis()));
        return value;
    }

    public List<TableEntry> listTables(String schema, String namePattern, String[] types,
                                       SqlSupplier<List<TableEntry>> loader) throws SQLException {
        if (!enabled()) return loader.get();
        ListKey key = ListKey.of(schema, namePattern, types);
        long now = System.currentTimeMillis();
        Entry<List<TableEntry>> cached = lists.get(key);
        if (cached != null && now - cached.loadedAtMs < ttlMs) {
            listHits.incrementAndGet();
            return cached.value;
        }
        listMisses.incrementAndGet();
        List<TableEntry> value = loader.get();
        if (maxEntries > 0 && lists.size() >= maxEntries) lists.clear();
        lists.put(key, new Entry<>(value, System.currentTimeMillis()));
        return value;
    }

    public void invalidateAll() {
        describes.clear();
        lists.clear();
    }

    public void invalidateSchema(String schema) {
        String norm = normalize(schema);
        if (norm == null) {
            invalidateAll();
            return;
        }
        Iterator<TableKey> ti = describes.keySet().iterator();
        while (ti.hasNext()) if (Objects.equals(norm, ti.next().schema)) ti.remove();
        Iterator<ListKey> li = lists.keySet().iterator();
        while (li.hasNext()) if (Objects.equals(norm, li.next().schema)) li.remove();
    }

    public void invalidateTable(String schema, String table) {
        if (table == null || table.isBlank()) return;
        describes.remove(TableKey.of(schema, table));
        // any list result that could include this table is now suspect; drop list cache for the schema
        String norm = normalize(schema);
        Iterator<ListKey> li = lists.keySet().iterator();
        while (li.hasNext()) if (Objects.equals(norm, li.next().schema)) li.remove();
    }

    public SchemaSnapshot snapshotInfo(String schema) {
        long now = System.currentTimeMillis();
        String norm = normalize(schema);

        // describes
        Map<String, List<CachedTableEntry>> bySchema = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<TableKey, Entry<TableDescription>> e : describes.entrySet()) {
            TableKey k = e.getKey();
            if (norm != null && !Objects.equals(norm, k.schema)) continue;
            String schemaLabel = k.schema == null ? "" : k.schema;
            List<CachedTableEntry> entries = bySchema.computeIfAbsent(schemaLabel, s -> new ArrayList<>());
            long ageMs = now - e.getValue().loadedAtMs;
            entries.add(new CachedTableEntry(k.table, ageMs / 1000L, ageMs >= ttlMs));
        }

        // lists
        List<ListCacheEntry> listEntries = new ArrayList<>();
        for (Map.Entry<ListKey, Entry<List<TableEntry>>> e : lists.entrySet()) {
            ListKey k = e.getKey();
            if (norm != null && !Objects.equals(norm, k.schema)) continue;
            long ageMs = now - e.getValue().loadedAtMs;
            listEntries.add(new ListCacheEntry(
                    k.schema,
                    k.pattern,
                    k.typesSig,
                    e.getValue().value.size(),
                    ageMs / 1000L,
                    ageMs >= ttlMs));
        }

        List<CachedSchemaEntry> schemas = new ArrayList<>();
        for (Map.Entry<String, List<CachedTableEntry>> e : bySchema.entrySet()) {
            schemas.add(new CachedSchemaEntry(e.getKey(), e.getValue().size(), e.getValue()));
        }
        return new SchemaSnapshot(
                enabled(),
                ttlMs / 1000L,
                maxEntries,
                describes.size(),
                lists.size(),
                describeHits.get(),
                describeMisses.get(),
                listHits.get(),
                listMisses.get(),
                Instant.ofEpochMilli(now).toString(),
                schemas,
                listEntries,
                norm);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
