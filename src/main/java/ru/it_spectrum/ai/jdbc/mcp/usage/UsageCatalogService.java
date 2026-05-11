package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jsqlparser.JSQLParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.ObservedColumnUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.ObservedTableUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticColumnUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTermEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.TableEvidenceProfile;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryColumnRef;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryParameter;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryTableRef;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QueryDetail;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByColumnResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByTableResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownDomainsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownSourceKindsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownTagsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ListQueriesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ObservedRelationshipsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.InvalidateUsageCatalogCacheResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ReresolveResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService.QueryModel;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageFieldUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageOutput;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageOutputColumn;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageParameter;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

@Service
public class UsageCatalogService {

    private static final Logger log = LoggerFactory.getLogger(UsageCatalogService.class);
    private static final int DEFAULT_PROFILE_LIMIT = 10;
    private static final int SOURCE_REF_PREVIEW_LIMIT = 20;

    private final UsageProperties properties;
    private final DataSource catalogDs;
    private final QueryAnalysisService analysis;
    private final JsonResponses json;
    private final JdbcMcpProperties jdbcMcpProperties;
    private final ObjectMapper mapper;
    private final List<UsageCatalogSource> catalogSources;
    private final MetadataService metadata;
    private final AtomicBoolean indexing = new AtomicBoolean(false);
    private volatile boolean indexReady = false;
    private volatile UsageCatalogStatus status;

    @Autowired
    public UsageCatalogService(UsageProperties properties,
                               DataSource usageDataSource,
                               QueryAnalysisService analysis,
                               JsonResponses json,
                               JdbcMcpProperties jdbcMcpProperties,
                               ObjectMapper mapper,
                               List<UsageCatalogSource> catalogSources,
                               MetadataService metadata) {
        this.properties = properties;
        this.catalogDs = usageDataSource;
        this.analysis = analysis;
        this.json = json;
        this.jdbcMcpProperties = jdbcMcpProperties;
        this.mapper = mapper;
        this.catalogSources = catalogSources == null ? List.of() : List.copyOf(catalogSources);
        this.metadata = metadata;
        this.status = UsageCatalogStatus.initial(properties.catalogEnabled(), configuredSources());
    }

    public UsageCatalogService(UsageProperties properties,
                               DataSource usageDataSource,
                               QueryAnalysisService analysis,
                               JsonResponses json,
                               DatabaseNativeUsageSourceProvider nativeProvider) {
        this.properties = properties;
        this.catalogDs = usageDataSource;
        this.analysis = analysis;
        this.json = json;
        this.jdbcMcpProperties = null;
        this.mapper = null;
        this.catalogSources = nativeProvider == null ? List.of() : List.of(nativeProvider);
        this.metadata = null;
        this.status = UsageCatalogStatus.initial(properties.catalogEnabled(), configuredSources());
    }

    public boolean enabled() {
        return properties.catalogEnabled();
    }

    // ---------------------------------------------------------------------------------------
    //  Rebuild
    // ---------------------------------------------------------------------------------------

    public InvalidateUsageCatalogCacheResult rebuild(List<QueryUsage> records) {
        if (!enabled()) {
            return new InvalidateUsageCatalogCacheResult(0);
        }
        List<QueryUsage> safeRecords = records == null ? List.of() : records;
        Counters counters = new Counters();
        long started = System.currentTimeMillis();
        log.info("H2 index rebuild: starting transaction for {} records", safeRecords.size());
        try (Connection conn = catalogDs.getConnection()) {
            conn.setAutoCommit(false);
            try {
                clearAll(conn);
                insertAll(conn, safeRecords, counters, true);
                conn.commit();
                log.info("H2 index rebuild: committed {} records ({} parseFailed, {} tables, {} joins, {} ms)",
                        safeRecords.size(), counters.parseFailed, counters.tablesExtracted,
                        counters.joinPairsExtracted, System.currentTimeMillis() - started);
                indexReady = true;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                log.warn("H2 index rebuild: rolled back ({})", e.getMessage());
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to rebuild usage catalog index: " + e.getMessage(), e);
        }

        return new InvalidateUsageCatalogCacheResult(safeRecords.size());
    }

    // ---------------------------------------------------------------------------------------
    //  Source loading / lazy indexing
    // ---------------------------------------------------------------------------------------

    public UsageCatalogStatus status() {
        return status;
    }

    public UsageCatalogStatus invalidateIndex() {
        if (!enabled()) return status;
        synchronized (this) {
            try (Connection conn = catalogDs.getConnection()) {
                conn.setAutoCommit(false);
                clearAll(conn);
                conn.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to invalidate usage catalog index: " + e.getMessage(), e);
            }
            indexReady = false;
            status = UsageCatalogStatus.initial(properties.catalogEnabled(), configuredSources())
                    .withState("invalidated");
            return status;
        }
    }

    public UsageCatalogStatus ensureIndexed() {
        if (!enabled() || indexReady) return status;
        synchronized (this) {
            if (!enabled() || indexReady) return status;
            if (!indexing.compareAndSet(false, true)) {
                return status;
            }
            status = UsageCatalogStatus.indexing(
                    properties.catalogEnabled(), configuredSources());
            try {
                log.info("ensureIndexed: starting index build...");
                long t = System.currentTimeMillis();
                LoadResult loaded = loadRecords();
                log.info("ensureIndexed: loadRecords completed: {} records, {} files, {} ms",
                        loaded.records().size(), loaded.filesScanned(), System.currentTimeMillis() - t);
                t = System.currentTimeMillis();
                InvalidateUsageCatalogCacheResult rebuildResult = rebuild(loaded.records());
                log.info("ensureIndexed: rebuild completed: {} records, {} ms",
                        rebuildResult.recordsLoaded(), System.currentTimeMillis() - t);
                t = System.currentTimeMillis();
                autoReresolve();
                log.info("ensureIndexed: reresolve completed, {} ms",
                        System.currentTimeMillis() - t);
                status = UsageCatalogStatus.ready(
                        properties.catalogEnabled(), configuredSources());
                indexReady = true;
                return status;
            } catch (RuntimeException e) {
                indexReady = false;
                status = UsageCatalogStatus.ready(
                        properties.catalogEnabled(), configuredSources())
                        .withState("failed");
                throw e;
            } finally {
                indexing.set(false);
            }
        }
    }

    public UsageCatalogStatus rebuildFromSources() {
        indexReady = false;
        return ensureIndexed();
    }

    private LoadResult loadRecords() {
        Map<String, QueryUsage> records = new LinkedHashMap<>();
        Set<String> duplicateKeys = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        int[] filesScanned = {0};
        for (Path source : resolvedCatalogPaths()) {
            try {
                loadSource(source, records, duplicateKeys, errors, filesScanned);
            } catch (RuntimeException | IOException e) {
                errors.add(source + ": " + e.getMessage());
            }
        }
        for (UsageCatalogSource source : catalogSources) {
            try {
                for (QueryUsage usage : source.load()) {
                    addRecord(source.name(), usage, records, duplicateKeys, errors);
                }
            } catch (Exception e) {
                errors.add(source.name() + ": " + e.getMessage());
            }
        }
        return new LoadResult(List.copyOf(records.values()), filesScanned[0],
                List.copyOf(errors), List.copyOf(duplicateKeys));
    }

    private void loadSource(Path source, Map<String, QueryUsage> records, Set<String> duplicateKeys,
                            List<String> errors, int[] filesScanned) throws IOException {
        if (mapper == null) return;
        if (!Files.exists(source)) {
            log.warn("Usage catalog source does not exist: {}", source);
            errors.add(source + ": source does not exist");
            return;
        }
        if (Files.isDirectory(source)) {
            try (Stream<Path> paths = Files.walk(source)) {
                List<Path> jsonFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(UsageCatalogService::isJsonFile)
                        .sorted()
                        .toList();
                for (Path file : jsonFiles) {
                    loadJson(file.toString(), Files.readAllBytes(file), records,
                            duplicateKeys, errors, filesScanned);
                }
            }
            return;
        }
        if (isZipFile(source)) {
            loadZip(source, records, duplicateKeys, errors, filesScanned);
            return;
        }
        if (isJsonFile(source)) {
            loadJson(source.toString(), Files.readAllBytes(source), records,
                    duplicateKeys, errors, filesScanned);
        }
    }

    private void loadZip(Path source, Map<String, QueryUsage> records, Set<String> duplicateKeys,
                         List<String> errors, int[] filesScanned) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();
            for (ZipEntry entry : entries) {
                try (InputStream in = zip.getInputStream(entry)) {
                    loadJson(source + "!" + entry.getName(), in.readAllBytes(), records,
                            duplicateKeys, errors, filesScanned);
                }
            }
        }
    }

    private void loadJson(String origin, byte[] bytes, Map<String, QueryUsage> records,
                          Set<String> duplicateKeys, List<String> errors, int[] filesScanned) {
        filesScanned[0]++;
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root.isObject()) {
                addRecord(origin, mapper.treeToValue(root, QueryUsage.class),
                        records, duplicateKeys, errors);
                return;
            }
            if (root.isArray()) {
                for (int i = 0; i < root.size(); i++) {
                    try {
                        addRecord(origin + "[" + i + "]",
                                mapper.treeToValue(root.get(i), QueryUsage.class),
                                records, duplicateKeys, errors);
                    } catch (RuntimeException | IOException e) {
                        errors.add(origin + "[" + i + "]: " + e.getMessage());
                    }
                }
                return;
            }
            errors.add(origin + ": expected QueryUsage object or array of QueryUsage objects");
        } catch (RuntimeException | IOException e) {
            errors.add(origin + ": " + e.getMessage());
        }
    }

    private void addRecord(String origin, QueryUsage usage, Map<String, QueryUsage> records,
                           Set<String> duplicateKeys, List<String> errors) {
        QueryUsageSource source = usage.source();
        if (source == null || source.kind() == null || source.path() == null) return;
        String unit = source.unit() == null ? "" : source.unit();
        String key = source.kind() + "\u0000" + source.path() + "\u0000" + unit;
        if (records.containsKey(key)) {
            duplicateKeys.add(key);
            errors.add(origin + ": duplicate source " + source.kind() + "/" + source.path()
                    + (unit.isEmpty() ? "" : "#" + unit));
            return;
        }
        records.put(key, usage);
    }

    private List<String> configuredSources() {
        List<String> out = new ArrayList<>(
                resolvedCatalogPaths().stream().map(Path::toString).toList());
        for (UsageCatalogSource source : catalogSources) {
            out.add(source.name());
        }
        return List.copyOf(out);
    }

    private List<Path> resolvedCatalogPaths() {
        List<Path> paths = new ArrayList<>();
        if (jdbcMcpProperties != null) {
            Path defaultDir = jdbcMcpProperties.usageCatalogDir();
            if (Files.isDirectory(defaultDir)) {
                paths.add(defaultDir);
            }
        }
        paths.addAll(properties.additionalCatalogPaths());
        return List.copyOf(paths);
    }

    private ReresolveResult autoReresolve() {
        if (metadata == null) return null;
        return reresolveInternal(name -> {
            List<TableEntry> matches = metadata.findTablesByName(name);
            List<String[]> out = new ArrayList<>(matches.size());
            for (TableEntry row : matches) {
                out.add(new String[]{row.schema(), row.name()});
            }
            return out;
        });
    }

    private static boolean isJsonFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private static boolean isZipFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private void insertAll(Connection conn, List<QueryUsage> records, Counters counters,
                           boolean throwOnDuplicate) throws SQLException {
        Set<String> seen = new LinkedHashSet<>();
        int total = records.size();
        int processed = 0;
        long lastLog = System.currentTimeMillis();
        long totalParseMs = 0;
        long totalInsertMs = 0;
        for (QueryUsage req : records) {
            validateRequest(req);
            QueryUsageSource src = req.source();
            String unit = src.unit() == null ? "" : src.unit();
            String key = src.kind() + "\u0000" + src.path() + "\u0000" + unit;
            if (!seen.add(key)) {
                if (throwOnDuplicate) {
                    throw new IllegalArgumentException("duplicate query source: "
                            + src.kind() + "/" + src.path()
                            + (unit.isEmpty() ? "" : "#" + unit));
                }
                continue;
            }

            QueryModel model;
            String parseStatus;
            String parseError = null;
            long t = System.currentTimeMillis();
            try {
                model = analysis.model(req.sql());
                parseStatus = "parsed";
            } catch (JSQLParserException | RuntimeException e) {
                model = new QueryModel();
                parseStatus = "failed";
                parseError = rootMessage(e);
                counters.parseFailed++;
            }
            totalParseMs += System.currentTimeMillis() - t;
            t = System.currentTimeMillis();
            long queryId = insertQuery(conn, req, src, unit, model, parseStatus, parseError);
            insertTags(conn, queryId, req.businessTags());
            counters.paramsStored += insertParams(conn, queryId, model, req.parameters());
            List<TableInsertResult> tableInserts = insertTables(conn, queryId, model);
            counters.tablesExtracted += tableInserts.size();
            counters.columnsExtracted += insertColumns(conn, queryId, model, tableInserts);
            counters.joinPairsExtracted += insertJoinPairs(conn, queryId, model);
            Map<String, Long> outputAliasToId = insertOutputs(conn, queryId, req.outputs());
            counters.outputsStored += outputAliasToId.size();
            counters.fieldUsagesStored += insertFieldUsages(conn, queryId, req.fieldUsages(), outputAliasToId);
            totalInsertMs += System.currentTimeMillis() - t;
            processed++;

            long now = System.currentTimeMillis();
            if (processed % 200 == 0 || now - lastLog > 5000) {
                log.info("insertAll progress: {}/{} records (failed: {}, parseAvg: {} ms, insertAvg: {} ms, total: {} ms)",
                        processed, total, counters.parseFailed,
                        totalParseMs / processed, totalInsertMs / processed, now - lastLog);
                lastLog = now;
            }
        }
        log.info("insertAll done: {}/{} records, {} parseFailed, {} parseMs, {} insertMs, {} tables, {} joins, {} columns",
                processed, total, counters.parseFailed, totalParseMs, totalInsertMs,
                counters.tablesExtracted, counters.joinPairsExtracted, counters.columnsExtracted);
    }

    private void validateRequest(QueryUsage req) {
        if (req == null) throw new IllegalArgumentException("payload is required");
        if (req.schemaVersion() == null || req.schemaVersion() != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (req.source() == null) throw new IllegalArgumentException("source is required");
        if (req.source().path() == null || req.source().path().isBlank()) {
            throw new IllegalArgumentException("source.path is required");
        }
        if (req.source().kind() == null || req.source().kind().isBlank()) {
            throw new IllegalArgumentException("source.kind is required");
        }
        if (req.sql() == null || req.sql().isBlank()) {
            throw new IllegalArgumentException("sql is required");
        }
        var kind = req.source().kind();
        var path = req.source().path();
        var unit = req.source().unit();
        if (kind.contains("/") || kind.contains("#")) {
            throw new IllegalArgumentException("source.kind must not contain '/' or '#': " + kind);
        }
        if (path.contains("#")) {
            throw new IllegalArgumentException("source.path must not contain '#': " + path);
        }
        if (unit != null && !unit.isEmpty() && (unit.contains("/") || unit.contains("#"))) {
            throw new IllegalArgumentException("source.unit must not contain '/' or '#': " + unit);
        }

        if (req.fieldUsages() != null) {
            for (QueryUsageFieldUsage fu : req.fieldUsages()) {
                if (fu == null) continue;
                if (fu.transformation() == null || fu.transformation().kind() == null) {
                    throw new IllegalArgumentException(
                            "fieldUsage.transformation.kind is required");
                }
            }
        }
    }

    private void clearAll(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM query")) {
            ps.executeUpdate();
        }
    }

    private long insertQuery(Connection conn, QueryUsage req,
                             QueryUsageSource src, String unit, QueryModel model,
                             String parseStatus, String parseError) throws SQLException {
        String sql = """
                INSERT INTO query (
                    source_kind, source_path, source_unit,
                    business_label, business_domain, raw_sql, normalized_sql,
                    parse_status, parse_error, source_meta_json, ingested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, src.kind());
            ps.setString(2, src.path());
            ps.setString(3, unit);
            ps.setString(4, req.businessLabel());
            ps.setString(5, req.businessDomain());
            ps.setString(6, req.sql());
            ps.setString(7, model.normalizedSql);
            ps.setString(8, parseStatus);
            ps.setString(9, parseError);
            ps.setString(10, req.sourceMeta() == null ? null : json.write(req.sourceMeta()));
            ps.setString(11, Instant.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("Failed to retrieve generated query id");
    }

    private void insertTags(Connection conn, long queryId, List<String> tags) throws SQLException {
        if (tags == null || tags.isEmpty()) return;
        String sql = "INSERT INTO query_tag (query_id, tag) VALUES (?, ?)";
        Set<String> uniqueTags = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) continue;
                String cleanTag = tag.trim();
                if (!uniqueTags.add(cleanTag)) continue;
                ps.setLong(1, queryId);
                ps.setString(2, cleanTag);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private int insertParams(Connection conn, long queryId, QueryModel model,
                             List<QueryUsageParameter> payloadParams) throws SQLException {
        List<ParamRow> rows = new ArrayList<>();
        Set<String> claimedNames = new LinkedHashSet<>();
        int ordinal = 0;

        for (QueryParameter parsed : model.parameters) {
            ordinal++;
            String name = stringValue(parsed.name());
            QueryUsageParameter payload = name != null ? findParamByName(payloadParams, name) : null;
            if (payload == null && payloadParams != null && ordinal - 1 < payloadParams.size()
                    && (name == null || payloadParams.get(ordinal - 1).name() == null)) {
                payload = payloadParams.get(ordinal - 1);
            }
            if (payload != null && payload.name() != null) claimedNames.add(payload.name());
            rows.add(new ParamRow(
                    ordinal,
                    name != null ? name : (payload != null ? payload.name() : null),
                    payload != null ? payload.dataType() : null,
                    payload != null ? payload.defaultValue() : null,
                    payload == null || payload.required() == null ? true : payload.required(),
                    payload != null ? payload.businessLabel() : null,
                    payload != null ? payload.businessDescription() : null
            ));
        }

        if (payloadParams != null) {
            for (QueryUsageParameter payload : payloadParams) {
                if (payload == null) continue;
                if (payload.name() != null && claimedNames.contains(payload.name())) continue;
                ordinal++;
                rows.add(new ParamRow(
                        ordinal,
                        payload.name(),
                        payload.dataType(),
                        payload.defaultValue(),
                        payload.required() == null ? true : payload.required(),
                        payload.businessLabel(),
                        payload.businessDescription()
                ));
            }
        }

        if (rows.isEmpty()) return 0;

        String sql = """
                INSERT INTO query_param (
                    query_id, ordinal, name, data_type, default_value,
                    required, business_label, business_description
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParamRow r : rows) {
                ps.setLong(1, queryId);
                ps.setInt(2, r.ordinal);
                ps.setString(3, r.name);
                ps.setString(4, r.dataType);
                ps.setString(5, r.defaultValue);
                ps.setInt(6, r.required ? 1 : 0);
                ps.setString(7, r.businessLabel);
                ps.setString(8, r.businessDescription);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return rows.size();
    }

    private List<TableInsertResult> insertTables(Connection conn, long queryId, QueryModel model) throws SQLException {
        if (model.tables.isEmpty()) return List.of();

        String sql = """
                INSERT INTO query_table (
                    query_id, raw_name, schema_resolved, table_resolved, alias, role, resolution_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        List<TableInsertResult> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (QueryTableRef table : model.tables) {
                String rawName = stringValue(table.name());
                String schema = stringValue(table.schema());
                String alias = stringValue(table.alias());
                String role = stringValue(table.source());
                if (role == null) role = "from";
                String resolutionStatus = model.cteNames.contains(rawName) ? "cte" : "unresolved";
                String resolvedTable = rawName == null ? null : rawName.toUpperCase(Locale.ROOT);
                String resolvedSchema = schema == null ? null : schema.toUpperCase(Locale.ROOT);

                ps.setLong(1, queryId);
                ps.setString(2, rawName);
                ps.setString(3, resolvedSchema);
                ps.setString(4, resolvedTable);
                ps.setString(5, alias);
                ps.setString(6, "cte".equals(resolutionStatus) ? "cte_ref" : role);
                ps.setString(7, resolutionStatus);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : -1L;
                    results.add(new TableInsertResult(id, rawName, alias, resolvedSchema, resolvedTable));
                }
            }
        }
        return results;
    }

    private int insertColumns(Connection conn, long queryId, QueryModel model,
                              List<TableInsertResult> tableInserts) throws SQLException {
        if (model.columns.isEmpty()) return 0;

        String sql = """
                INSERT INTO query_column (
                    query_id, query_table_id, schema_resolved, table_resolved, column_name, context
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (QueryColumnRef col : model.columns) {
                String columnName = stringValue(col.name());
                if (columnName == null) continue;
                String qualifier = stringValue(col.qualifier());
                String context = stringValue(col.context());
                Resolved resolved = resolveQualifier(qualifier, model.aliases);
                Long tableId = matchTableId(tableInserts, qualifier, resolved);

                ps.setLong(1, queryId);
                if (tableId == null) ps.setNull(2, java.sql.Types.INTEGER);
                else ps.setLong(2, tableId);
                ps.setString(3, resolved.schema);
                ps.setString(4, resolved.table);
                ps.setString(5, columnName.toUpperCase(Locale.ROOT));
                ps.setString(6, context == null ? "select" : context);
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    private int insertJoinPairs(Connection conn, long queryId, QueryModel model) throws SQLException {
        if (model.joinPairs.isEmpty()) return 0;

        String sql = """
                INSERT INTO query_join (
                    query_id, join_type,
                    left_schema, left_table, left_column,
                    right_schema, right_table, right_column,
                    on_text, equality
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """;
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (QueryModel.JoinPair pair : model.joinPairs) {
                Resolved left = resolveQualifier(pair.leftQualifier(), model.aliases);
                Resolved right = resolveQualifier(pair.rightQualifier(), model.aliases);
                ps.setLong(1, queryId);
                ps.setString(2, pair.joinType());
                ps.setString(3, left.schema);
                ps.setString(4, left.table);
                ps.setString(5, pair.leftColumn() == null ? null : pair.leftColumn().toUpperCase(Locale.ROOT));
                ps.setString(6, right.schema);
                ps.setString(7, right.table);
                ps.setString(8, pair.rightColumn() == null ? null : pair.rightColumn().toUpperCase(Locale.ROOT));
                ps.setString(9, pair.onText());
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    private Map<String, Long> insertOutputs(Connection conn, long queryId,
                                            List<QueryUsageOutput> outputs) throws SQLException {
        if (outputs == null || outputs.isEmpty()) return Map.of();
        Map<String, Long> aliasToId = new LinkedHashMap<>();
        String outputSql = """
                INSERT INTO query_output (
                    query_id, alias, source_expression, business_label, business_description
                ) VALUES (?, ?, ?, ?, ?)
                """;
        String columnSql = """
                INSERT INTO query_output_column (
                    query_output_id, schema_resolved, table_resolved, column_name
                ) VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement po = conn.prepareStatement(outputSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement pc = conn.prepareStatement(columnSql)) {
            for (QueryUsageOutput out : outputs) {
                if (out == null || out.alias() == null || out.alias().isBlank()) continue;
                if (aliasToId.containsKey(out.alias())) continue;
                po.setLong(1, queryId);
                po.setString(2, out.alias());
                po.setString(3, out.sourceExpression());
                po.setString(4, out.businessLabel());
                po.setString(5, out.businessDescription());
                po.executeUpdate();
                long outputId;
                try (ResultSet keys = po.getGeneratedKeys()) {
                    if (!keys.next()) continue;
                    outputId = keys.getLong(1);
                }
                aliasToId.put(out.alias(), outputId);
                if (out.derivedFromColumns() != null) {
                    Set<String> uniqueColumns = new LinkedHashSet<>();
                    for (QueryUsageOutputColumn oc : out.derivedFromColumns()) {
                        if (oc == null || oc.column() == null || oc.column().isBlank()) continue;
                        String schema = oc.schema() == null ? null : oc.schema().toUpperCase(Locale.ROOT);
                        String table = oc.table() == null ? null : oc.table().toUpperCase(Locale.ROOT);
                        String column = oc.column().toUpperCase(Locale.ROOT);
                        String colKey = schema + "\u0000" + table + "\u0000" + column;
                        if (!uniqueColumns.add(colKey)) continue;
                        pc.setLong(1, outputId);
                        pc.setString(2, schema);
                        pc.setString(3, table);
                        pc.setString(4, column);
                        pc.addBatch();
                    }
                }
            }
            pc.executeBatch();
        }
        return aliasToId;
    }

    private int insertFieldUsages(Connection conn, long queryId, List<QueryUsageFieldUsage> usages,
                                  Map<String, Long> outputAliasToId) throws SQLException {
        if (usages == null || usages.isEmpty()) return 0;
        String sql = """
                INSERT INTO query_field_usage (
                    query_id, query_output_id, business_object,
                    transformation_kind, transformation_description,
                    location_kind, location_details_json, headers_json, confidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (QueryUsageFieldUsage fu : usages) {
                if (fu == null) continue;
                Long outputId = fu.output() == null ? null : outputAliasToId.get(fu.output());
                ps.setLong(1, queryId);
                if (outputId == null) ps.setNull(2, java.sql.Types.INTEGER);
                else ps.setLong(2, outputId);
                ps.setString(3, fu.businessObject());
                ps.setString(4, fu.transformation().kind().json());
                ps.setString(5, fu.transformation().description());
                ps.setString(6, fu.location() == null ? null : fu.location().kind());
                ps.setString(7, fu.location() == null || fu.location().details() == null
                        ? null : json.write(fu.location().details()));
                ps.setString(8, fu.headers() == null ? null : json.write(fu.headers()));
                ps.setString(9, fu.confidence() == null ? null : fu.confidence().json());
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    // ---------------------------------------------------------------------------------------
    //  Read side
    // ---------------------------------------------------------------------------------------

    public QueryDetail getQuery(String sourceKind, String sourcePath, String sourceUnit) {
        if (sourceKind == null || sourceKind.isBlank()) throw new IllegalArgumentException("sourceKind is required");
        if (sourcePath == null || sourcePath.isBlank()) throw new IllegalArgumentException("sourcePath is required");
        ensureIndexed();
        String unit = sourceUnit == null ? "" : sourceUnit;
        QueryDetail head = querySingle(
                "SELECT * FROM query WHERE source_kind = ? AND source_path = ? AND source_unit = ?",
                ps -> {
                    ps.setString(1, sourceKind);
                    ps.setString(2, sourcePath);
                    ps.setString(3, unit);
                },
                this::catalogQueryRow);
        if (head == null) {
            throw new IllegalArgumentException("query not found: " + sourceKind + "/" + sourcePath
                    + (unit.isEmpty() ? "" : "#" + unit));
        }
        long queryId = querySingle(
                "SELECT id FROM query WHERE source_kind = ? AND source_path = ? AND source_unit = ?",
                ps -> {
                    ps.setString(1, sourceKind);
                    ps.setString(2, sourcePath);
                    ps.setString(3, unit);
                },
                rs -> rs.getLong("id"));
        List<String> tags = queryList(
                "SELECT tag FROM query_tag WHERE query_id = ? ORDER BY tag",
                ps -> ps.setLong(1, queryId),
                rs -> rs.getString("tag"));
        List<QueryDetail.Param> params = queryList(
                "SELECT * FROM query_param WHERE query_id = ? ORDER BY ordinal",
                ps -> ps.setLong(1, queryId),
                this::paramRow);
        List<QueryDetail.Table> tables = queryList(
                "SELECT * FROM query_table WHERE query_id = ? ORDER BY id",
                ps -> ps.setLong(1, queryId),
                this::tableRow);
        List<QueryDetail.Column> columns = queryList(
                "SELECT * FROM query_column WHERE query_id = ? ORDER BY id",
                ps -> ps.setLong(1, queryId),
                this::columnRow);
        List<QueryDetail.JoinPair> joinPairs = queryList(
                "SELECT * FROM query_join WHERE query_id = ? ORDER BY id",
                ps -> ps.setLong(1, queryId),
                this::joinRow);
        List<QueryDetail.Output> outputs = queryList(
                "SELECT * FROM query_output WHERE query_id = ? ORDER BY id",
                ps -> ps.setLong(1, queryId),
                rs -> outputRow(rs));
        List<QueryDetail.FieldUsage> fieldUsages = queryList(
                "SELECT * FROM query_field_usage WHERE query_id = ? ORDER BY id",
                ps -> ps.setLong(1, queryId),
                this::fieldUsageRow);
        return new QueryDetail(
                head.sourceKind(), head.sourcePath(), head.sourceUnit(),
                head.businessLabel(), head.businessDomain(), head.rawSql(), head.normalizedSql(),
                head.parseStatus(),
                tags, params, tables, columns, joinPairs, outputs, fieldUsages);
    }

    public ListQueriesResult listQueries(String sourcePath, String sourceKind,
                                          String businessDomain, String tag, String parseStatus,
                                          Integer limit, Integer offset) {
        ensureIndexed();
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT q.source_kind, q.source_path, q.source_unit,
                   q.business_label, q.business_domain, q.parse_status
                FROM query q
                """);
        List<Object> args = new ArrayList<>();
        if (tag != null && !tag.isBlank()) {
            sql.append(" JOIN query_tag t ON t.query_id = q.id AND t.tag = ?");
            args.add(tag);
        }
        sql.append(" WHERE 1=1");
        if (sourceKind != null && !sourceKind.isBlank()) {
            sql.append(" AND q.source_kind = ?");
            args.add(sourceKind);
        }
        if (sourcePath != null && !sourcePath.isBlank()) {
            sql.append(" AND q.source_path LIKE ?");
            args.add(sourcePath);
        }
        if (businessDomain != null && !businessDomain.isBlank()) {
            sql.append(" AND q.business_domain = ?");
            args.add(businessDomain);
        }
        if (parseStatus != null && !parseStatus.isBlank()) {
            sql.append(" AND q.parse_status = ?");
            args.add(parseStatus);
        }
        sql.append(" ORDER BY q.source_kind, q.source_path, q.source_unit");
        int safeLimit = limit == null || limit <= 0 ? 100 : Math.min(limit, 1000);
        sql.append(" LIMIT ?");
        args.add(safeLimit);
        int safeOffset = offset == null || offset < 0 ? 0 : offset;
        sql.append(" OFFSET ?");
        args.add(safeOffset);

        List<ListQueriesResult.QueryEntry> entries = queryList(sql.toString(), ps -> {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
        }, rs -> new ListQueriesResult.QueryEntry(
                rs.getString("source_kind"),
                rs.getString("source_path"),
                emptyToNull(rs.getString("source_unit")),
                rs.getString("business_label"),
                rs.getString("business_domain"),
                rs.getString("parse_status")));

        return new ListQueriesResult(entries, safeLimit, safeOffset, entries.size());
    }

    public FindQueriesByTableResult findQueriesByTable(String schema, String table) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
        ensureIndexed();
        String tableUpper = table.toUpperCase(Locale.ROOT);
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
        String sql = """
                SELECT q.source_kind, q.source_path, q.source_unit,
                       q.business_label, q.business_domain,
                       qt.role, qt.alias, qt.raw_name, qt.schema_resolved, qt.table_resolved
                FROM query_table qt
                JOIN query q ON q.id = qt.query_id
                WHERE qt.table_resolved = ?
                  AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL)
                ORDER BY q.source_kind, q.source_path, q.source_unit
                """;
        List<FindQueriesByTableResult.Match> matches = queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
        }, rs -> new FindQueriesByTableResult.Match(
                rs.getString("source_kind"),
                rs.getString("source_path"),
                emptyToNull(rs.getString("source_unit")),
                rs.getString("business_label"),
                rs.getString("business_domain"),
                rs.getString("role"),
                rs.getString("alias"),
                rs.getString("raw_name"),
                rs.getString("schema_resolved"),
                rs.getString("table_resolved")));
        return new FindQueriesByTableResult(schemaUpper, tableUpper, matches, matches.size());
    }

    public FindQueriesByColumnResult findQueriesByColumn(String schema, String table, String column) {
        if (column == null || column.isBlank()) throw new IllegalArgumentException("column is required");
        ensureIndexed();
        String columnUpper = column.toUpperCase(Locale.ROOT);
        String tableUpper = table == null || table.isBlank() ? null : table.toUpperCase(Locale.ROOT);
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
        String sql = """
                SELECT q.source_kind, q.source_path, q.source_unit, q.business_label, q.business_domain,
                       qc.context, qc.schema_resolved, qc.table_resolved, qc.column_name
                FROM query_column qc
                JOIN query q ON q.id = qc.query_id
                WHERE qc.column_name = ?
                  AND (? IS NULL OR qc.table_resolved = ? OR qc.table_resolved IS NULL)
                  AND (? IS NULL OR qc.schema_resolved = ? OR qc.schema_resolved IS NULL)
                ORDER BY q.source_kind, q.source_path, q.source_unit, qc.context
                """;
        List<FindQueriesByColumnResult.Match> matches = queryList(sql, ps -> {
            ps.setString(1, columnUpper);
            ps.setString(2, tableUpper);
            ps.setString(3, tableUpper);
            ps.setString(4, schemaUpper);
            ps.setString(5, schemaUpper);
        }, rs -> new FindQueriesByColumnResult.Match(
                rs.getString("source_kind"),
                rs.getString("source_path"),
                emptyToNull(rs.getString("source_unit")),
                rs.getString("business_label"),
                rs.getString("business_domain"),
                rs.getString("context"),
                rs.getString("schema_resolved"),
                rs.getString("table_resolved"),
                rs.getString("column_name")));
        return new FindQueriesByColumnResult(schemaUpper, tableUpper, columnUpper, matches, matches.size());
    }

    public ObservedRelationshipsResult observedRelationships(String schema, String table, int minSupport) {
        ensureIndexed();
        int support = Math.max(1, minSupport);
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
        String tableUpper = table == null || table.isBlank() ? null : table.toUpperCase(Locale.ROOT);
        String sql = """
                SELECT
                    left_schema, left_table, left_column,
                    right_schema, right_table, right_column,
                    COUNT(*) AS support,
                    STRING_AGG(query_id, '|') AS ids
                FROM query_join
                WHERE equality = 1
                  AND left_table IS NOT NULL AND right_table IS NOT NULL
                  AND (? IS NULL OR left_table = ? OR right_table = ?)
                  AND (? IS NULL OR left_schema = ? OR right_schema = ? OR left_schema IS NULL OR right_schema IS NULL)
                GROUP BY left_schema, left_table, left_column, right_schema, right_table, right_column
                HAVING COUNT(*) >= ?
                ORDER BY support DESC, left_table, right_table
                """;
        List<ObservedRelationshipsResult.Relationship> rels = queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, tableUpper);
            ps.setString(3, tableUpper);
            ps.setString(4, schemaUpper);
            ps.setString(5, schemaUpper);
            ps.setString(6, schemaUpper);
            ps.setInt(7, support);
        }, rs -> {
            String ids = rs.getString("ids");
            List<QuerySourceRef> sourceRefs = resolveSourceRefs(ids);
            return new ObservedRelationshipsResult.Relationship(
                    new ObservedRelationshipsResult.SchemaRef(
                            rs.getString("left_schema"),
                            rs.getString("left_table"),
                            rs.getString("left_column")),
                    new ObservedRelationshipsResult.SchemaRef(
                            rs.getString("right_schema"),
                            rs.getString("right_table"),
                            rs.getString("right_column")),
                    rs.getInt("support"),
                    sourceRefs);
        });
        return new ObservedRelationshipsResult(schemaUpper, tableUpper, support, rels, rels.size());
    }

    public KnownTagsResult listKnownTags() {
        ensureIndexed();
        String sql = """
                SELECT t.tag, COUNT(*) AS count
                FROM query_tag t
                JOIN query q ON q.id = t.query_id
                GROUP BY t.tag ORDER BY count DESC, t.tag
                """;
        List<KnownTagsResult.TagEntry> entries = queryList(sql, ps -> {
        }, rs -> new KnownTagsResult.TagEntry(
                rs.getString("tag"),
                rs.getInt("count")));
        return new KnownTagsResult(entries);
    }

    public List<ObservedEdge> observedEdges(Set<String> tableFilter, int minSupport) {
        if (!enabled()) return List.of();
        ensureIndexed();
        int support = Math.max(1, minSupport);
        String sql = """
                SELECT
                    qj.left_schema, qj.left_table, qj.left_column,
                    qj.right_schema, qj.right_table, qj.right_column,
                    q.source_kind, q.source_path, q.source_unit
                FROM query_join qj
                JOIN query q ON q.id = qj.query_id
                WHERE qj.equality = 1
                  AND qj.left_table IS NOT NULL AND qj.right_table IS NOT NULL
                  AND qj.left_column IS NOT NULL AND qj.right_column IS NOT NULL
                ORDER BY qj.left_table, qj.left_column, qj.right_table, qj.right_column
                """;
        List<ObservedEdgeRow> rows = queryList(sql,
                ps -> {},
                rs -> new ObservedEdgeRow(
                        rs.getString("left_schema"),
                        rs.getString("left_table"),
                        rs.getString("left_column"),
                        rs.getString("right_schema"),
                        rs.getString("right_table"),
                        rs.getString("right_column"),
                        new QuerySourceRef(
                                rs.getString("source_kind"),
                                rs.getString("source_path"),
                                emptyToNull(rs.getString("source_unit")))));

        Map<String, ObservedEdgeAggregator> byPair = new LinkedHashMap<>();
        for (ObservedEdgeRow row : rows) {
            String key = undirectedPairKey(row.leftTable(), row.leftColumn(), row.rightTable(), row.rightColumn());
            ObservedEdgeAggregator acc = byPair.computeIfAbsent(key, ignored -> new ObservedEdgeAggregator(
                    row.leftSchema(), row.leftTable(), row.leftColumn(),
                    row.rightSchema(), row.rightTable(), row.rightColumn()));
            acc.sourceRefs.add(row.sourceRef());
        }

        List<ObservedEdge> all = byPair.values().stream()
                .filter(a -> a.sourceRefs.size() >= support)
                .map(a -> new ObservedEdge(
                        a.leftSchema, a.leftTable, a.leftColumn,
                        a.rightSchema, a.rightTable, a.rightColumn,
                        a.sourceRefs.size(),
                        capSourceRefs(new ArrayList<>(a.sourceRefs), SOURCE_REF_PREVIEW_LIMIT)))
                .sorted((a, b) -> Integer.compare(b.support(), a.support()))
                .toList();

        if (tableFilter == null || tableFilter.isEmpty()) return all;
        List<ObservedEdge> filtered = new ArrayList<>();
        for (ObservedEdge edge : all) {
            if (tableFilter.contains(edge.leftTable()) || tableFilter.contains(edge.rightTable())) {
                filtered.add(edge);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    private record ObservedEdgeRow(
            String leftSchema, String leftTable, String leftColumn,
            String rightSchema, String rightTable, String rightColumn,
            QuerySourceRef sourceRef
    ) {}

    private static class ObservedEdgeAggregator {
        final String leftSchema, leftTable, leftColumn;
        final String rightSchema, rightTable, rightColumn;
        final Set<QuerySourceRef> sourceRefs = new LinkedHashSet<>();

        ObservedEdgeAggregator(String leftSchema, String leftTable, String leftColumn,
                               String rightSchema, String rightTable, String rightColumn) {
            this.leftSchema = leftSchema;
            this.leftTable = leftTable;
            this.leftColumn = leftColumn;
            this.rightSchema = rightSchema;
            this.rightTable = rightTable;
            this.rightColumn = rightColumn;
        }
    }

    public ReresolveResult reresolve(NameLookup lookup) {
        return reresolveInternal(lookup);
    }

    private ReresolveResult reresolveInternal(NameLookup lookup) {
        if (lookup == null) throw new IllegalArgumentException("lookup is required");
        Set<String> names = collectUnresolvedTableNames();
        if (names.isEmpty()) {
            return new ReresolveResult();
        }
        log.info("reresolve: {} unresolved names to check", names.size());
        int resolved = 0;
        int ambiguous = 0;
        int unresolved = 0;

        try (Connection conn = catalogDs.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String name : names) {
                    List<String[]> matches;
                    try {
                        matches = lookup.findByName(name);
                    } catch (Exception e) {
                        log.warn("reresolve: lookup failed for {}: {}", name, e.getMessage());
                        continue;
                    }
                    if (matches == null || matches.isEmpty()) {
                        applyResolution(conn, name, null, "unresolved");
                        unresolved++;
                    } else if (matches.size() == 1) {
                        applyResolution(conn, name, matches.get(0)[0].toUpperCase(Locale.ROOT), "resolved");
                        resolved++;
                    } else {
                        applyResolution(conn, name, null, "ambiguous");
                        ambiguous++;
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Catalog write failed: " + e.getMessage(), e);
        }

        return new ReresolveResult();
    }

    private Set<String> collectUnresolvedTableNames() {
        String sql = """
                SELECT DISTINCT qt.table_resolved
                FROM query_table qt
                JOIN query q ON q.id = qt.query_id
                WHERE qt.table_resolved IS NOT NULL
                  AND qt.schema_resolved IS NULL
                  AND qt.resolution_status <> 'cte'
                """;
        return new LinkedHashSet<>(queryList(sql, ps -> {
        }, rs -> rs.getString(1)));
    }

    private void applyResolution(Connection conn, String tableUpper,
                                 String schemaUpper, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query_table SET schema_resolved = ?, resolution_status = ?"
                        + " WHERE table_resolved = ? AND schema_resolved IS NULL"
                        + " AND resolution_status <> 'cte'")) {
            ps.setString(1, schemaUpper);
            ps.setString(2, status);
            ps.setString(3, tableUpper);
            ps.executeUpdate();
        }
        if (!"resolved".equals(status)) return;

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query_column SET schema_resolved = ?"
                        + " WHERE table_resolved = ? AND schema_resolved IS NULL")) {
            ps.setString(1, schemaUpper);
            ps.setString(2, tableUpper);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query_join SET left_schema = ?"
                        + " WHERE left_table = ? AND left_schema IS NULL")) {
            ps.setString(1, schemaUpper);
            ps.setString(2, tableUpper);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query_join SET right_schema = ?"
                        + " WHERE right_table = ? AND right_schema IS NULL")) {
            ps.setString(1, schemaUpper);
            ps.setString(2, tableUpper);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------------------------------
    //  Evidence layer
    // ---------------------------------------------------------------------------------------

    public KnownDomainsResult listKnownDomains() {
        ensureIndexed();
        String sql = """
                SELECT business_domain, COUNT(*) AS count
                FROM query
                WHERE business_domain IS NOT NULL
                GROUP BY business_domain ORDER BY count DESC, business_domain
                """;
        List<KnownDomainsResult.DomainEntry> entries = queryList(sql, ps -> {
        }, rs -> new KnownDomainsResult.DomainEntry(
                rs.getString("business_domain"),
                rs.getInt("count")));
        return new KnownDomainsResult(entries);
    }

    public KnownSourceKindsResult listKnownSourceKinds() {
        ensureIndexed();
        String sql = """
                SELECT source_kind, COUNT(*) AS count
                FROM query
                GROUP BY source_kind ORDER BY count DESC, source_kind
                """;
        List<KnownSourceKindsResult.KindEntry> entries = queryList(sql, ps -> {
        }, rs -> new KnownSourceKindsResult.KindEntry(
                rs.getString("source_kind"),
                rs.getInt("count")));
        return new KnownSourceKindsResult(entries);
    }

    public TableEvidenceProfile tableEvidenceProfile(String schema, String table) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
        ensureIndexed();
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
        String tableUpper = table.toUpperCase(Locale.ROOT);
        List<QuerySourceRef> sourceRefs = tableQuerySourceRefs(schemaUpper, tableUpper);
        ObservedTableUsage observed = new ObservedTableUsage(
                sourceRefs.size(),
                capSourceRefs(sourceRefs, SOURCE_REF_PREVIEW_LIMIT),
                observedColumnUsages(schemaUpper, tableUpper));
        SemanticTableUsage semantic = new SemanticTableUsage(
                tableQueryTerms(schemaUpper, tableUpper, "q.business_domain", "query_table qt", null),
                tableQueryTerms(schemaUpper, tableUpper, "tag.tag", "query_table qt JOIN query_tag tag ON tag.query_id = qt.query_id", null),
                tableQueryTerms(schemaUpper, tableUpper, "q.business_label", "query_table qt", null),
                outputTerms(schemaUpper, tableUpper, "qo.business_label", null),
                outputTerms(schemaUpper, tableUpper, "qfu.business_object",
                        "JOIN query_field_usage qfu ON qfu.query_output_id = qoc.query_output_id"),
                semanticColumnUsages(schemaUpper, tableUpper));
        return new TableEvidenceProfile(schemaUpper, tableUpper, observed, semantic);
    }

    public SemanticEdgeEvidence semanticEdgeEvidence(String leftSchema, String leftTable,
                                                      String rightSchema, String rightTable) {
        if (!enabled()) return null;
        if (leftTable == null || leftTable.isBlank()) return null;
        if (rightTable == null || rightTable.isBlank()) return null;
        ensureIndexed();
        String leftSchemaUpper = leftSchema == null || leftSchema.isBlank()
                ? null : leftSchema.toUpperCase(Locale.ROOT);
        String rightSchemaUpper = rightSchema == null || rightSchema.isBlank()
                ? null : rightSchema.toUpperCase(Locale.ROOT);
        String leftTableUpper = leftTable.toUpperCase(Locale.ROOT);
        String rightTableUpper = rightTable.toUpperCase(Locale.ROOT);

        List<Long> coIds = coOccurringQueryIds(
                leftSchemaUpper, leftTableUpper, rightSchemaUpper, rightTableUpper);
        if (coIds.isEmpty()) {
            return new SemanticEdgeEvidence(List.of(), List.of(), List.of(), 0, List.of());
        }
        List<QuerySourceRef> coSourceRefs = loadSourceRefs(coIds);
        return new SemanticEdgeEvidence(
                sharedTerms(coIds, "q.business_domain", "query q",
                        "q.business_domain IS NOT NULL AND q.business_domain <> ''", "q.id"),
                sharedTerms(coIds, "qfu.business_object", "query_field_usage qfu",
                        "qfu.business_object IS NOT NULL AND qfu.business_object <> ''", "qfu.query_id"),
                sharedTerms(coIds, "qo.business_label", "query_output qo",
                        "qo.business_label IS NOT NULL AND qo.business_label <> ''", "qo.query_id"),
                coIds.size(),
                capSourceRefs(coSourceRefs, SOURCE_REF_PREVIEW_LIMIT));
    }

    private List<Long> coOccurringQueryIds(String leftSchemaUpper, String leftTableUpper,
                                            String rightSchemaUpper, String rightTableUpper) {
        String sql = """
                SELECT DISTINCT q.id
                FROM query q
                WHERE EXISTS (SELECT 1 FROM query_table qt WHERE qt.query_id = q.id
                              AND qt.table_resolved = ?
                              AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL))
                  AND EXISTS (SELECT 1 FROM query_table qt WHERE qt.query_id = q.id
                              AND qt.table_resolved = ?
                              AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL))
                ORDER BY q.id
                """;
        return queryList(sql, ps -> {
            ps.setString(1, leftTableUpper);
            ps.setString(2, leftSchemaUpper);
            ps.setString(3, leftSchemaUpper);
            ps.setString(4, rightTableUpper);
            ps.setString(5, rightSchemaUpper);
            ps.setString(6, rightSchemaUpper);
        }, rs -> rs.getLong("id"));
    }

    private List<SemanticTermEvidence> sharedTerms(List<Long> queryIds,
                                                   String valueExpression,
                                                   String fromExpression,
                                                   String whereExtra,
                                                   String idColumn) {
        if (queryIds == null || queryIds.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(queryIds.size(), "?"));
        String sql = ("""
                SELECT %s AS term_value,
                       COUNT(DISTINCT %s) AS support,
                       STRING_AGG(%s, '|') AS ids
                FROM %s
                WHERE %s
                  AND %s IN (%s)
                GROUP BY %s
                ORDER BY support DESC, term_value
                LIMIT ?
                """).formatted(valueExpression, idColumn, idColumn, fromExpression,
                whereExtra, idColumn, placeholders, valueExpression);
        return queryList(sql, ps -> {
            int i = 1;
            for (Long id : queryIds) ps.setLong(i++, id);
            ps.setInt(i, DEFAULT_PROFILE_LIMIT);
        }, this::termEvidenceRow);
    }

    public List<SemanticTableCandidate> semanticTableCandidates(String schema, String terms, int limit) {
        if (!enabled()) return List.of();
        ensureIndexed();
        List<String> tokens = semanticTokens(terms);
        if (tokens.isEmpty()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
        String tokenClause = String.join(" OR ", Collections.nCopies(tokens.size(), "LOWER(term_value) LIKE ?"));
        String sql = """
                SELECT schema_name, table_name, source_kind, term_value,
                       COUNT(DISTINCT query_id) AS support,
                       STRING_AGG(query_id, '|') AS ids
                FROM (
                    SELECT qt.schema_resolved AS schema_name, qt.table_resolved AS table_name,
                           'business_domain' AS source_kind, q.business_domain AS term_value, q.id AS query_id
                    FROM query_table qt
                    JOIN query q ON q.id = qt.query_id
                    WHERE q.business_domain IS NOT NULL AND q.business_domain <> ''
                    UNION ALL
                    SELECT qt.schema_resolved AS schema_name, qt.table_resolved AS table_name,
                           'business_tag' AS source_kind, tag.tag AS term_value, q.id AS query_id
                    FROM query_table qt
                    JOIN query q ON q.id = qt.query_id
                    JOIN query_tag tag ON tag.query_id = q.id
                    UNION ALL
                    SELECT qt.schema_resolved AS schema_name, qt.table_resolved AS table_name,
                           'query_label' AS source_kind, q.business_label AS term_value, q.id AS query_id
                    FROM query_table qt
                    JOIN query q ON q.id = qt.query_id
                    WHERE q.business_label IS NOT NULL AND q.business_label <> ''
                    UNION ALL
                    SELECT qoc.schema_resolved AS schema_name, qoc.table_resolved AS table_name,
                           'output_label' AS source_kind, qo.business_label AS term_value, qo.query_id AS query_id
                    FROM query_output_column qoc
                    JOIN query_output qo ON qo.id = qoc.query_output_id
                    WHERE qo.business_label IS NOT NULL AND qo.business_label <> ''
                    UNION ALL
                    SELECT qoc.schema_resolved AS schema_name, qoc.table_resolved AS table_name,
                           'business_object' AS source_kind, qfu.business_object AS term_value, qo.query_id AS query_id
                    FROM query_output_column qoc
                    JOIN query_output qo ON qo.id = qoc.query_output_id
                    JOIN query_field_usage qfu ON qfu.query_output_id = qo.id
                    WHERE qfu.business_object IS NOT NULL AND qfu.business_object <> ''
                ) semantic_terms
                WHERE table_name IS NOT NULL
                  AND (? IS NULL OR schema_name = ? OR schema_name IS NULL)
                  AND (%s)
                GROUP BY schema_name, table_name, source_kind, term_value
                ORDER BY support DESC, table_name, source_kind, term_value
                LIMIT ?
                """.formatted(tokenClause);
        List<SemanticTermRow> rows = queryList(sql, ps -> {
            int i = 1;
            ps.setString(i++, schemaUpper);
            ps.setString(i++, schemaUpper);
            for (String token : tokens) ps.setString(i++, "%" + token + "%");
            ps.setInt(i, safeLimit * 5);
        }, rs -> new SemanticTermRow(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("source_kind"),
                rs.getString("term_value"),
                rs.getInt("support"),
                resolveSourceRefs(rs.getString("ids"))));
        return aggregateSemanticCandidates(rows, safeLimit);
    }

    private List<SemanticTableCandidate> aggregateSemanticCandidates(List<SemanticTermRow> rows, int limit) {
        Map<String, SemanticCandidateAccumulator> byTable = new LinkedHashMap<>();
        for (SemanticTermRow row : rows) {
            String key = (row.schema() == null ? "" : row.schema()) + "\u0000" + row.table();
            SemanticCandidateAccumulator acc = byTable.computeIfAbsent(key,
                    ignored -> new SemanticCandidateAccumulator(row.schema(), row.table()));
            acc.support += row.support();
            acc.sourceRefs.addAll(row.sourceRefs());
            acc.terms.add(new SemanticTermEvidence(
                    row.sourceKind() + ":" + row.termValue(),
                    row.support(),
                    capSourceRefs(row.sourceRefs(), SOURCE_REF_PREVIEW_LIMIT)));
        }
        return byTable.values().stream()
                .map(acc -> new SemanticTableCandidate(
                        acc.schema,
                        acc.table,
                        acc.support,
                        capTerms(acc.terms, DEFAULT_PROFILE_LIMIT),
                        capSourceRefs(new ArrayList<>(acc.sourceRefs), SOURCE_REF_PREVIEW_LIMIT)))
                .sorted((a, b) -> {
                    int bySupport = Integer.compare(b.support(), a.support());
                    return bySupport != 0 ? bySupport : a.table().compareToIgnoreCase(b.table());
                })
                .limit(limit)
                .toList();
    }

    private List<QuerySourceRef> tableQuerySourceRefs(String schemaUpper, String tableUpper) {
        String sql = """
                SELECT DISTINCT q.source_kind, q.source_path, q.source_unit
                FROM query_table qt
                JOIN query q ON q.id = qt.query_id
                WHERE qt.table_resolved = ?
                  AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL)
                ORDER BY q.source_kind, q.source_path, q.source_unit
                """;
        return queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
        }, rs -> new QuerySourceRef(
                rs.getString("source_kind"),
                rs.getString("source_path"),
                emptyToNull(rs.getString("source_unit"))));
    }

    private List<ObservedColumnUsage> observedColumnUsages(String schemaUpper, String tableUpper) {
        String sql = """
                SELECT qc.column_name, qc.context,
                       q.source_kind, q.source_path, q.source_unit
                FROM query_column qc
                JOIN query q ON q.id = qc.query_id
                WHERE qc.table_resolved = ?
                  AND (? IS NULL OR qc.schema_resolved = ? OR qc.schema_resolved IS NULL)
                ORDER BY qc.column_name, qc.context
                """;
        Map<String, ColumnUsageAccumulator> byColumn = new LinkedHashMap<>();
        List<ObservedColumnRow> rows = queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
        }, rs -> new ObservedColumnRow(
                rs.getString("column_name"),
                rs.getString("context"),
                new QuerySourceRef(
                        rs.getString("source_kind"),
                        rs.getString("source_path"),
                        emptyToNull(rs.getString("source_unit")))));
        for (ObservedColumnRow row : rows) {
            String columnKey = row.columnName() == null ? "" : row.columnName();
            ColumnUsageAccumulator acc = byColumn.computeIfAbsent(columnKey, ColumnUsageAccumulator::new);
            acc.sourceRefs.add(row.sourceRef());
            acc.contexts.add(row.context());
        }
        return byColumn.values().stream()
                .map(acc -> {
                    Set<String> uniqueContexts = new LinkedHashSet<>(acc.contexts);
                    List<SemanticTermEvidence> contextEvidences = new ArrayList<>();
                    for (String ctx : uniqueContexts) {
                        contextEvidences.add(new SemanticTermEvidence(
                                ctx, 1, List.of()));
                    }
                    return new ObservedColumnUsage(
                            acc.column,
                            acc.sourceRefs.size(),
                            contextEvidences,
                            capSourceRefs(new ArrayList<>(acc.sourceRefs), SOURCE_REF_PREVIEW_LIMIT));
                })
                .toList();
    }

    private record ObservedColumnRow(String columnName, String context, QuerySourceRef sourceRef) {}

    private List<SemanticColumnUsage> semanticColumnUsages(String schemaUpper, String tableUpper) {
        Map<String, SemanticColumnAccumulator> byColumn = new LinkedHashMap<>();
        for (ColumnTerm term : outputColumnTerms(schemaUpper, tableUpper, "qo.business_label",
                "\nJOIN query_output qo ON qo.id = qoc.query_output_id")) {
            byColumn.computeIfAbsent(term.column(), SemanticColumnAccumulator::new)
                    .outputLabels.add(term.evidence());
        }
        for (ColumnTerm term : outputColumnTerms(schemaUpper, tableUpper, "qfu.business_object",
                "\nJOIN query_output qo ON qo.id = qoc.query_output_id\nJOIN query_field_usage qfu ON qfu.query_output_id = qo.id")) {
            byColumn.computeIfAbsent(term.column(), SemanticColumnAccumulator::new)
                    .businessObjects.add(term.evidence());
        }
        return byColumn.values().stream()
                .map(acc -> new SemanticColumnUsage(acc.column, acc.outputLabels, acc.businessObjects))
                .toList();
    }

    private List<SemanticTermEvidence> tableQueryTerms(String schemaUpper, String tableUpper,
                                                       String valueExpression,
                                                       String fromExpression,
                                                       String extraJoin) {
        String join = extraJoin == null || extraJoin.isBlank() ? "" : "\n" + extraJoin;
        String sql = """
                SELECT %s AS term_value,
                       COUNT(DISTINCT q.id) AS support,
                       STRING_AGG(q.id, '|') AS ids
                FROM %s
                JOIN query q ON q.id = qt.query_id%s
                WHERE qt.table_resolved = ?
                  AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL)
                  AND %s IS NOT NULL AND %s <> ''
                GROUP BY %s
                ORDER BY support DESC, term_value
                LIMIT ?
                """.formatted(valueExpression, fromExpression, join, valueExpression,
                valueExpression, valueExpression);
        return queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
            ps.setInt(4, DEFAULT_PROFILE_LIMIT);
        }, this::termEvidenceRow);
    }

    private List<SemanticTermEvidence> outputTerms(String schemaUpper, String tableUpper,
                                                   String valueExpression,
                                                   String extraJoin) {
        String join = extraJoin == null || extraJoin.isBlank() ? "" : "\n" + extraJoin;
        String sql = """
                SELECT %s AS term_value,
                       COUNT(DISTINCT qo.query_id) AS support,
                       STRING_AGG(qo.query_id, '|') AS ids
                FROM query_output_column qoc
                JOIN query_output qo ON qo.id = qoc.query_output_id%s
                WHERE qoc.table_resolved = ?
                  AND (? IS NULL OR qoc.schema_resolved = ? OR qoc.schema_resolved IS NULL)
                  AND %s IS NOT NULL AND %s <> ''
                GROUP BY %s
                ORDER BY support DESC, term_value
                LIMIT ?
                """.formatted(valueExpression, join, valueExpression, valueExpression, valueExpression);
        return queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
            ps.setInt(4, DEFAULT_PROFILE_LIMIT);
        }, this::termEvidenceRow);
    }

    private List<ColumnTerm> outputColumnTerms(String schemaUpper, String tableUpper,
                                               String valueExpression,
                                               String extraJoin) {
        String join = extraJoin == null || extraJoin.isBlank() ? "" : "\n" + extraJoin;
        String sql = """
                SELECT qoc.column_name,
                       %s AS term_value,
                       COUNT(DISTINCT qoc.query_output_id) AS support,
                       STRING_AGG(qoc.query_output_id, '|') AS ids
                FROM query_output_column qoc%s
                WHERE qoc.table_resolved = ?
                  AND (? IS NULL OR qoc.schema_resolved = ? OR qoc.schema_resolved IS NULL)
                  AND %s IS NOT NULL AND %s <> ''
                GROUP BY qoc.column_name, %s
                ORDER BY qoc.column_name, support DESC
                LIMIT ?
                """.formatted(valueExpression, join, valueExpression, valueExpression, valueExpression);
        return queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
            ps.setInt(4, DEFAULT_PROFILE_LIMIT * 5);
        }, rs -> new ColumnTerm(
                rs.getString("column_name"),
                termEvidenceRow(rs)));
    }

    // ---------------------------------------------------------------------------------------
    //  Row mappers
    // ---------------------------------------------------------------------------------------

    private QueryDetail catalogQueryRow(ResultSet rs) throws SQLException {
        return new QueryDetail(
                rs.getString("source_kind"),
                rs.getString("source_path"),
                emptyToNull(rs.getString("source_unit")),
                rs.getString("business_label"),
                rs.getString("business_domain"),
                rs.getString("raw_sql"),
                rs.getString("normalized_sql"),
                rs.getString("parse_status"),
                null, null, null, null, null, null, null
        );
    }

    private QueryDetail.Param paramRow(ResultSet rs) throws SQLException {
        return new QueryDetail.Param(
                rs.getInt("ordinal"),
                rs.getString("name"),
                rs.getString("data_type"),
                rs.getString("default_value"),
                rs.getInt("required") != 0,
                rs.getString("business_label"),
                rs.getString("business_description"));
    }

    private QueryDetail.Table tableRow(ResultSet rs) throws SQLException {
        return new QueryDetail.Table(
                rs.getString("raw_name"),
                rs.getString("schema_resolved"),
                rs.getString("table_resolved"),
                rs.getString("alias"),
                rs.getString("role"),
                rs.getString("resolution_status"));
    }

    private QueryDetail.Column columnRow(ResultSet rs) throws SQLException {
        return new QueryDetail.Column(
                rs.getString("schema_resolved"),
                rs.getString("table_resolved"),
                rs.getString("column_name"),
                rs.getString("context"));
    }

    private QueryDetail.JoinPair joinRow(ResultSet rs) throws SQLException {
        return new QueryDetail.JoinPair(
                rs.getString("join_type"),
                new QueryDetail.JoinPair.SchemaRef(
                        rs.getString("left_schema"),
                        rs.getString("left_table"),
                        rs.getString("left_column")),
                new QueryDetail.JoinPair.SchemaRef(
                        rs.getString("right_schema"),
                        rs.getString("right_table"),
                        rs.getString("right_column")),
                rs.getString("on_text"),
                rs.getInt("equality") != 0);
    }

    private QueryDetail.Output outputRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        List<QueryDetail.Output.DerivedColumn> derived = queryList(
                "SELECT * FROM query_output_column WHERE query_output_id = ?",
                ps -> ps.setLong(1, id),
                cs -> new QueryDetail.Output.DerivedColumn(
                        cs.getString("schema_resolved"),
                        cs.getString("table_resolved"),
                        cs.getString("column_name")));
        return new QueryDetail.Output(
                rs.getString("alias"),
                rs.getString("source_expression"),
                rs.getString("business_label"),
                rs.getString("business_description"),
                derived);
    }

    private QueryDetail.FieldUsage fieldUsageRow(ResultSet rs) throws SQLException {
        return new QueryDetail.FieldUsage(
                rs.getString("business_object"),
                new QueryDetail.FieldUsage.Transformation(
                        rs.getString("transformation_kind"),
                        rs.getString("transformation_description")),
                new QueryDetail.FieldUsage.Location(
                        rs.getString("location_kind")),
                rs.getString("confidence"));
    }

    private SemanticTermEvidence termEvidenceRow(ResultSet rs) throws SQLException {
        return new SemanticTermEvidence(
                rs.getString("term_value"),
                rs.getInt("support"),
                capSourceRefs(resolveSourceRefs(rs.getString("ids")), SOURCE_REF_PREVIEW_LIMIT));
    }

    // ---------------------------------------------------------------------------------------
    //  Small helpers
    // ---------------------------------------------------------------------------------------

    private record Resolved(String schema, String table) {
    }

    private Resolved resolveQualifier(String qualifier, Map<String, String> aliases) {
        if (qualifier == null || qualifier.isBlank()) return new Resolved(null, null);
        String mapped = aliases == null ? null : aliases.get(qualifier);
        String fq = mapped == null ? qualifier : mapped;
        if ("(subquery)".equals(fq)) return new Resolved(null, null);
        int dot = fq.lastIndexOf('.');
        if (dot < 0) return new Resolved(null, fq.toUpperCase(Locale.ROOT));
        return new Resolved(
                fq.substring(0, dot).toUpperCase(Locale.ROOT),
                fq.substring(dot + 1).toUpperCase(Locale.ROOT));
    }

    private Long matchTableId(List<TableInsertResult> tables, String qualifier, Resolved resolved) {
        if (qualifier == null || qualifier.isBlank() || resolved.table == null) return null;
        Long byAlias = null;
        for (TableInsertResult t : tables) {
            boolean schemaMatch = (resolved.schema == null || "".equals(resolved.schema))
                    ? t.schemaResolved == null
                    : resolved.schema.equals(t.schemaResolved);
            if (!schemaMatch) continue;
            if (resolved.table.equals(t.alias)) {
                byAlias = t.id;
            }
            if (resolved.table.equals(t.tableResolved)) {
                return t.id;
            }
        }
        return byAlias;
    }

    private QueryUsageParameter findParamByName(List<QueryUsageParameter> payloadParams, String name) {
        if (payloadParams == null || name == null) return null;
        for (QueryUsageParameter p : payloadParams) {
            if (p != null && name.equals(p.name())) return p;
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private List<QuerySourceRef> resolveSourceRefs(String ids) {
        if (ids == null || ids.isBlank()) return List.of();
        Set<Long> idSet = new LinkedHashSet<>();
        for (String part : ids.split("\\|")) {
            if (part == null || part.isBlank()) continue;
            try {
                idSet.add(Long.parseLong(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return loadSourceRefs(new ArrayList<>(idSet));
    }

    private List<QuerySourceRef> loadSourceRefs(List<Long> queryIds) {
        if (queryIds == null || queryIds.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(queryIds.size(), "?"));
        String sql = "SELECT source_kind, source_path, source_unit FROM query WHERE id IN (" + placeholders + ") ORDER BY source_kind, source_path, source_unit";
        return queryList(sql, ps -> {
            for (int i = 0; i < queryIds.size(); i++) ps.setLong(i + 1, queryIds.get(i));
        }, rs -> new QuerySourceRef(
                rs.getString("source_kind"),
                rs.getString("source_path"),
                emptyToNull(rs.getString("source_unit"))));
    }

    private static List<QuerySourceRef> capSourceRefs(List<QuerySourceRef> refs, int limit) {
        if (refs == null || refs.isEmpty()) return List.of();
        int safeLimit = Math.max(0, limit);
        if (refs.size() <= safeLimit) return List.copyOf(refs);
        return List.copyOf(refs.subList(0, safeLimit));
    }

    private static List<SemanticTermEvidence> capTerms(List<SemanticTermEvidence> values, int limit) {
        if (values == null || values.isEmpty()) return List.of();
        int safeLimit = Math.max(0, limit);
        if (values.size() <= safeLimit) return List.copyOf(values);
        return List.copyOf(values.subList(0, safeLimit));
    }

    private static List<String> semanticTokens(String terms) {
        if (terms == null || terms.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : terms.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() < 2) continue;
            if (seen.add(raw)) out.add(raw);
        }
        return out;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }

    private static String undirectedPairKey(String tableA, String columnA, String tableB, String columnB) {
        String a = tableA == null ? "" : tableA + "." + (columnA == null ? "" : columnA);
        String b = tableB == null ? "" : tableB + "." + (columnB == null ? "" : columnB);
        return a.compareTo(b) <= 0 ? a + "==" + b : b + "==" + a;
    }

    // ---------------------------------------------------------------------------------------
    //  Query helpers
    // ---------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface PsConsumer {
        void apply(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> T querySingle(String sql, PsConsumer binder, RowMapper<T> mapper) {
        try (Connection conn = catalogDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.apply(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapper.map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Catalog read failed: " + e.getMessage(), e);
        }
    }

    private <T> List<T> queryList(String sql, PsConsumer binder, RowMapper<T> mapper) {
        List<T> out = new ArrayList<>();
        try (Connection conn = catalogDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.apply(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapper.map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Catalog read failed: " + e.getMessage(), e);
        }
        return Collections.unmodifiableList(out);
    }

    // ---------------------------------------------------------------------------------------
    //  Internal record types
    // ---------------------------------------------------------------------------------------

    public record ObservedEdge(
            String leftSchema, String leftTable, String leftColumn,
            String rightSchema, String rightTable, String rightColumn,
            int support, List<QuerySourceRef> sourceRefs
    ) {
    }

    @FunctionalInterface
    public interface NameLookup {
        List<String[]> findByName(String tableName) throws Exception;
    }

    private record TableInsertResult(long id, String rawName, String alias,
                                      String schemaResolved, String tableResolved) {
    }

    private record ColumnTerm(String column, SemanticTermEvidence evidence) {
    }

    private record LoadResult(List<QueryUsage> records, int filesScanned,
                              List<String> errors, List<String> duplicateKeys) {
    }

    private record SemanticTermRow(String schema, String table, String sourceKind,
                                   String termValue, int support, List<QuerySourceRef> sourceRefs) {
    }

    private record ParamRow(int ordinal, String name, String dataType, String defaultValue,
                            boolean required, String businessLabel, String businessDescription) {
    }

    private static class Counters {
        int parseFailed;
        int paramsStored;
        int tablesExtracted;
        int columnsExtracted;
        int joinPairsExtracted;
        int outputsStored;
        int fieldUsagesStored;
    }

    private static class ColumnUsageAccumulator {
        final String column;
        final Set<QuerySourceRef> sourceRefs = new LinkedHashSet<>();
        final List<String> contexts = new ArrayList<>();

        ColumnUsageAccumulator(String column) {
            this.column = column;
        }
    }

    private static class SemanticColumnAccumulator {
        final String column;
        final List<SemanticTermEvidence> outputLabels = new ArrayList<>();
        final List<SemanticTermEvidence> businessObjects = new ArrayList<>();

        SemanticColumnAccumulator(String column) {
            this.column = column;
        }
    }

    private static class SemanticCandidateAccumulator {
        final String schema;
        final String table;
        int support;
        final Set<QuerySourceRef> sourceRefs = new LinkedHashSet<>();
        final List<SemanticTermEvidence> terms = new ArrayList<>();

        SemanticCandidateAccumulator(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }
    }

    private record SemanticTermRowList(String schema, String table, String sourceKind,
                                        String termValue, int support, List<QuerySourceRef> sourceRefs) {
    }
}