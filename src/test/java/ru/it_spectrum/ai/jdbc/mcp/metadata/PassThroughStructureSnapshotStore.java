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
 * No-persistence {@link StructureSnapshotStore} for tests that exercise live database behaviour:
 * every read runs its loader, nothing is cached, and the build/invalidate operations are no-ops.
 * Behaviourally equivalent to the old disabled in-memory cache.
 */
public class PassThroughStructureSnapshotStore implements StructureSnapshotStore {

    @Override
    public CatalogSnapshotInfo snapshotInfo() {
        return new CatalogSnapshotInfo(0, 0, null, List.of());
    }

    @Override
    public List<TableEntry> listTables(String schema, String namePattern, String[] types,
                                       SqlSupplier<List<TableEntry>> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public TableDescription describeTable(String schema, String table,
                                          SqlSupplier<TableDescription> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public TableDescription peekDescribeTable(String schema, String table) {
        return null;
    }

    @Override
    public List<TableDescription> listSnapshotTableDescriptions() {
        return List.of();
    }

    @Override
    public void saveAll(Collection<TableDescription> tables) {
        // no-op
    }

    @Override
    public List<Map<String, Object>> views(String schema,
                                           SqlSupplier<List<Map<String, Object>>> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public String viewDefinition(String schema, String name, SqlSupplier<String> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public List<RoutineEntry> routines(String schema, String namePattern,
                                       SqlSupplier<List<RoutineEntry>> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public String routineSource(String schema, String name, SqlSupplier<String> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public List<Trigger> triggers(String schema, boolean includeDefinition,
                                  SqlSupplier<List<Trigger>> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public String triggerDefinition(String schema, String table, String trigger,
                                    SqlSupplier<String> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public List<SequenceEntry> sequences(String schema, SqlSupplier<List<SequenceEntry>> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public List<SearchObjectEntry> searchObjects(String namePattern,
                                                 SqlSupplier<List<SearchObjectEntry>> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public List<TableEntry> findTablesByName(String name, SqlSupplier<List<TableEntry>> loader) throws SQLException {
        return loader.get();
    }

    @Override
    public void rebuild(StructureSnapshotData data) {
        // no-op
    }

    @Override
    public void invalidateAll() {
        // no-op
    }

    @Override
    public void invalidateSchema(String schema) {
        // no-op
    }

    @Override
    public void invalidateTable(String schema, String table) {
        // no-op
    }
}
