package ru.it_spectrum.ai.jdbc.mcp.metadata;

import ru.it_spectrum.ai.jdbc.mcp.model.metadata.RoutineEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SearchObjectEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SequenceEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Trigger;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.CatalogSnapshotInfo;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistent "cache forever" snapshot of database <em>structure</em> (tables, columns, keys,
 * views, routines, triggers, sequences). Replaces the former in-memory TTL cache.
 *
 * <p>Semantics: a snapshot row is authoritative until explicitly invalidated (soft, via the
 * {@code invalidate*} methods) or the backing file is deleted (hard). There is no staleness
 * detection.
 *
 * <p>Read paths take a {@code loader} that runs the live database query. {@code describeTable} is a
 * pure key/value cache: a miss runs the loader and persists the result. The pattern/object methods
 * ({@code listTables}, {@code views}, {@code routines}, {@code triggers}, {@code sequences},
 * {@code searchObjects}, {@code findTablesByName}) are <em>scope-gated</em>: they serve from the
 * snapshot only for schemas recorded in {@code catalog_meta.structure_schemas} and otherwise fall
 * through to the loader. The full scope is front-loaded by {@link #rebuild}.
 *
 * <p>The store is pure persistence and never touches the live database itself — the live access is
 * entirely encapsulated in the loaders supplied by {@code MetadataService}.
 */
public interface StructureSnapshotStore {

    @FunctionalInterface
    interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    /** Current catalog format, structure version, build time, and covered schema scope. */
    CatalogSnapshotInfo snapshotInfo() throws SQLException;

    // ---------- tables ----------

    List<TableEntry> listTables(String schema, String namePattern, String[] types,
                                SqlSupplier<List<TableEntry>> loader) throws SQLException;

    TableDescription describeTable(String schema, String table,
                                   SqlSupplier<TableDescription> loader) throws SQLException;

    /** Snapshot lookup for a single table without running any loader; {@code null} when absent. */
    TableDescription peekDescribeTable(String schema, String table) throws SQLException;

    /** Full descriptions currently persisted in the snapshot; never queries the live database. */
    List<TableDescription> listSnapshotTableDescriptions() throws SQLException;

    /** Persist (insert-or-replace) full descriptions: detail JSON + flat column/FK projection. */
    void saveAll(Collection<TableDescription> tables) throws SQLException;

    // ---------- objects (loader = live implementation) ----------

    List<Map<String, Object>> views(String schema,
                                    SqlSupplier<List<Map<String, Object>>> loader) throws SQLException;

    String viewDefinition(String schema, String name,
                          SqlSupplier<String> loader) throws SQLException;

    List<RoutineEntry> routines(String schema, String namePattern,
                                SqlSupplier<List<RoutineEntry>> loader) throws SQLException;

    String routineSource(String schema, String name,
                         SqlSupplier<String> loader) throws SQLException;

    List<Trigger> triggers(String schema, boolean includeDefinition,
                           SqlSupplier<List<Trigger>> loader) throws SQLException;

    String triggerDefinition(String schema, String table, String trigger,
                             SqlSupplier<String> loader) throws SQLException;

    List<SequenceEntry> sequences(String schema,
                                  SqlSupplier<List<SequenceEntry>> loader) throws SQLException;

    // ---------- derived over the snapshot (fallthrough to loader outside scope) ----------

    List<SearchObjectEntry> searchObjects(String namePattern,
                                          SqlSupplier<List<SearchObjectEntry>> loader) throws SQLException;

    List<TableEntry> findTablesByName(String name,
                                      SqlSupplier<List<TableEntry>> loader) throws SQLException;

    // ---------- build-all (one transaction; replaces the whole structure snapshot) ----------

    /** Carrier for a full rebuild: everything {@code MetadataService} loaded from the live DB. */
    record StructureSnapshotData(
            List<String> schemas,
            List<TableDescription> tables,
            List<ViewRecord> views,
            List<RoutineRecord> routines,
            List<Trigger> triggers,
            List<SequenceEntry> sequences
    ) {}

    record ViewRecord(String schema, String name, String definition) {}

    record RoutineRecord(String schema, String name, String type, String source) {}

    /** Replace the entire structure snapshot atomically and stamp {@code catalog_meta}. */
    void rebuild(StructureSnapshotData data) throws SQLException;

    // ---------- invalidation (soft clear) ----------

    void invalidateAll() throws SQLException;

    void invalidateSchema(String schema) throws SQLException;

    void invalidateTable(String schema, String table) throws SQLException;
}
