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
import ru.it_spectrum.ai.jdbc.mcp.model.usage.CatalogQueryDetail;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByColumnResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByTableResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownDomainsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownTagsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ListQueriesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ObservedRelationshipsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.IndexerStatusResponse;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.RebuildResult;
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

/**
 * Core service for the local usage catalog.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Rebuild the runtime index from canonical {@link QueryUsage} records loaded from files.</li>
 *   <li>Read-side lookups used by the {@code findQueriesBy*} and {@code observedRelationships}
 *       MCP tools.</li>
 * </ul>
 *
 * <p>Resolution strategy: table and column qualifiers are resolved via the parser's alias map and
 * uppercased for case-insensitive matching. During source indexing, unqualified table references
 * are also resolved against the live JDBC schema when possible.
 */
@Service
public class UsageCatalogService {

    private static final Logger log = LoggerFactory.getLogger(UsageCatalogService.class);
    private static final int DEFAULT_PROFILE_LIMIT = 10;
    private static final int QUERY_UID_PREVIEW_LIMIT = 20;

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
    private volatile IndexerStatusResponse status;

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
        this.status = IndexerStatusResponse.initial(properties.catalogEnabled(), configuredSources());
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
        this.status = IndexerStatusResponse.initial(properties.catalogEnabled(), configuredSources());
    }

    public boolean enabled() {
        return properties.catalogEnabled();
    }

    // ---------------------------------------------------------------------------------------
    //  Rebuild
    // ---------------------------------------------------------------------------------------

    public RebuildResult rebuild(List<QueryUsage> records) {
        if (!enabled()) {
            return new RebuildResult(0, 0, 0, 0, 0, 0, 0, 0, 0);
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

        return new RebuildResult(
                safeRecords.size(),
                counters.parseFailed,
                counters.paramsStored,
                counters.tablesExtracted,
                counters.columnsExtracted,
                counters.joinPairsExtracted,
                counters.outputsStored,
                counters.fieldUsagesStored,
                System.currentTimeMillis() - started
        );
    }

    // ---------------------------------------------------------------------------------------
    //  Source loading / lazy indexing
    // ---------------------------------------------------------------------------------------

    public IndexerStatusResponse status() {
        return status;
    }

    public IndexerStatusResponse invalidateIndex() {
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
            status = IndexerStatusResponse.initial(properties.catalogEnabled(), configuredSources())
                    .withState("invalidated");
            return status;
        }
    }

    public IndexerStatusResponse ensureIndexed() {
        if (!enabled() || indexReady) return status;
        synchronized (this) {
            if (!enabled() || indexReady) return status;
            if (!indexing.compareAndSet(false, true)) {
                return status;
            }
            long started = System.currentTimeMillis();
            String startedAt = Instant.ofEpochMilli(started).toString();
            status = IndexerStatusResponse.indexing(
                    properties.catalogEnabled(), configuredSources());
            try {
                log.info("ensureIndexed: starting index build...");
                long t = System.currentTimeMillis();
                LoadResult loaded = loadRecords();
                log.info("ensureIndexed: loadRecords completed: {} records, {} files, {} ms",
                        loaded.records().size(), loaded.filesScanned(), System.currentTimeMillis() - t);
                t = System.currentTimeMillis();
                RebuildResult rebuildResult = rebuild(loaded.records());
                log.info("ensureIndexed: rebuild completed: {} records ({} parseFailed, {} joins), {} ms",
                        rebuildResult.recordsLoaded(), rebuildResult.parseFailed(),
                        rebuildResult.joinPairsExtracted(), System.currentTimeMillis() - t);
                t = System.currentTimeMillis();
                ReresolveResult reresolve = autoReresolve();
                log.info("ensureIndexed: reresolve completed: {} resolved, {} ambiguous, {} unresolved, {} ms",
                        reresolve != null ? reresolve.tablesResolved() : 0,
                        reresolve != null ? reresolve.tablesAmbiguous() : 0,
                        reresolve != null ? reresolve.tablesUnresolved() : 0,
                        System.currentTimeMillis() - t);
                status = IndexerStatusResponse.ready(
                        properties.catalogEnabled(), "ready", configuredSources(),
                        startedAt, loaded.filesScanned(), loaded.records().size(),
                        loaded.errors().size(), loaded.duplicateUids().size(),
                        loaded.errors(), loaded.duplicateUids(),
                        Instant.now().toString(), Instant.now().toString(),
                        System.currentTimeMillis() - started,
                        rebuildResult.parseFailed(), rebuildResult.paramsStored(),
                        rebuildResult.tablesExtracted(), rebuildResult.columnsExtracted(),
                        rebuildResult.joinPairsExtracted(), rebuildResult.outputsStored(),
                        rebuildResult.fieldUsagesStored(), rebuildResult.indexBuildMs(),
                        reresolve == null ? null : reresolve.tablesResolved(),
                        reresolve == null ? null : reresolve.tablesAmbiguous(),
                        reresolve == null ? null : reresolve.tablesUnresolved()
                );
                indexReady = true;
                return status;
            } catch (RuntimeException e) {
                indexReady = false;
                status = IndexerStatusResponse.ready(
                        properties.catalogEnabled(), "failed", configuredSources(),
                        startedAt, 0, 0, 1, 0,
                        List.of("index rebuild failed: " + e.getMessage()), List.of(),
                        null, Instant.now().toString(),
                        System.currentTimeMillis() - started,
                        null, null, null, null, null, null, null, null,
                        null, null, null
                );
                throw e;
            } finally {
                indexing.set(false);
            }
        }
    }

    public IndexerStatusResponse rebuildFromSources() {
        indexReady = false;
        return ensureIndexed();
    }

    private LoadResult loadRecords() {
        Map<String, QueryUsage> records = new LinkedHashMap<>();
        Set<String> duplicateUids = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        int[] filesScanned = {0};
        for (Path source : resolvedCatalogPaths()) {
            try {
                loadSource(source, records, duplicateUids, errors, filesScanned);
            } catch (RuntimeException | IOException e) {
                errors.add(source + ": " + e.getMessage());
            }
        }
        for (UsageCatalogSource source : catalogSources) {
            try {
                for (QueryUsage usage : source.load()) {
                    addRecord(source.name(), usage, records, duplicateUids, errors);
                }
            } catch (Exception e) {
                errors.add(source.name() + ": " + e.getMessage());
            }
        }
        return new LoadResult(List.copyOf(records.values()), filesScanned[0],
                List.copyOf(errors), List.copyOf(duplicateUids));
    }

    private void loadSource(Path source, Map<String, QueryUsage> records, Set<String> duplicateUids,
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
                            duplicateUids, errors, filesScanned);
                }
            }
            return;
        }
        if (isZipFile(source)) {
            loadZip(source, records, duplicateUids, errors, filesScanned);
            return;
        }
        if (isJsonFile(source)) {
            loadJson(source.toString(), Files.readAllBytes(source), records,
                    duplicateUids, errors, filesScanned);
        }
    }

    private void loadZip(Path source, Map<String, QueryUsage> records, Set<String> duplicateUids,
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
                            duplicateUids, errors, filesScanned);
                }
            }
        }
    }

    private void loadJson(String origin, byte[] bytes, Map<String, QueryUsage> records,
                          Set<String> duplicateUids, List<String> errors, int[] filesScanned) {
        filesScanned[0]++;
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root.isObject()) {
                addRecord(origin, mapper.treeToValue(root, QueryUsage.class),
                        records, duplicateUids, errors);
                return;
            }
            if (root.isArray()) {
                for (int i = 0; i < root.size(); i++) {
                    try {
                        addRecord(origin + "[" + i + "]",
                                mapper.treeToValue(root.get(i), QueryUsage.class),
                                records, duplicateUids, errors);
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
                           Set<String> duplicateUids, List<String> errors) {
        QueryUsageSource source = usage.source();
        String unit = source == null ? null : source.unit();
        String uid = UsageUid.build(source == null ? null : source.kind(),
                source == null ? null : source.path(), unit);
        if (records.containsKey(uid)) {
            duplicateUids.add(uid);
            errors.add(origin + ": duplicate uid " + uid);
            return;
        }
        records.put(uid, usage);
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

    /**
     * Insert a batch of records into the H2 index. When {@code throwOnDuplicate} is {@code true},
     * a duplicate uid raises an {@link IllegalArgumentException}; when {@code false}, duplicates
     * are silently skipped.
     */
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
            String uid = UsageUid.build(src.kind(), src.path(), unit);
            if (!seen.add(uid)) {
                if (throwOnDuplicate) {
                    throw new IllegalArgumentException("duplicate query uid: " + uid);
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
            insertAnalyzed(conn, uid, req, src, unit, model, parseStatus, parseError, counters);
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
        UsageUid.validate(req.source().kind(), req.source().path(), req.source().unit());

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

    private void insertAnalyzed(Connection conn, String uid, QueryUsage req,
                                QueryUsageSource src, String unit, QueryModel model,
                                String parseStatus, String parseError,
                                Counters counters) throws SQLException {
        insertQuery(conn, uid, req, src, unit, model, parseStatus, parseError);
        insertTags(conn, uid, req.businessTags());
        counters.paramsStored += insertParams(conn, uid, model, req.parameters());
        List<TableInsertResult> tableInserts = insertTables(conn, uid, model);
        counters.tablesExtracted += tableInserts.size();
        counters.columnsExtracted += insertColumns(conn, uid, model, tableInserts);
        counters.joinPairsExtracted += insertJoinPairs(conn, uid, model);
        Map<String, Long> outputAliasToId = insertOutputs(conn, uid, req.outputs());
        counters.outputsStored += outputAliasToId.size();
        counters.fieldUsagesStored += insertFieldUsages(conn, uid, req.fieldUsages(), outputAliasToId);
    }

    private void insertQuery(Connection conn, String uid, QueryUsage req,
                             QueryUsageSource src, String unit, QueryModel model,
                             String parseStatus, String parseError) throws SQLException {
        String sql = """
                INSERT INTO query (
                    uid, source_kind, source_path, source_unit,
                    business_label, business_domain, raw_sql, normalized_sql,
                    parse_status, parse_error, source_meta_json, ingested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uid);
            ps.setString(2, src.kind());
            ps.setString(3, src.path());
            ps.setString(4, unit);
            ps.setString(5, req.businessLabel());
            ps.setString(6, req.businessDomain());
            ps.setString(7, req.sql());
            ps.setString(8, model.normalizedSql);
            ps.setString(9, parseStatus);
            ps.setString(10, parseError);
            ps.setString(11, req.sourceMeta() == null ? null : json.write(req.sourceMeta()));
            ps.setString(12, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private void insertTags(Connection conn, String uid, List<String> tags) throws SQLException {
        if (tags == null || tags.isEmpty()) return;
        String sql = "INSERT INTO query_tag (query_uid, tag) VALUES (?, ?)";
        Set<String> uniqueTags = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) continue;
                String cleanTag = tag.trim();
                if (!uniqueTags.add(cleanTag)) continue;
                ps.setString(1, uid);
                ps.setString(2, cleanTag);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Merges parser-extracted parameters with caller-provided semantics. Matching is by name when
     * present (both sides), otherwise by ordinal. Caller-only entries (a parameter the parser did
     * not see, e.g. because parsing failed or the value is hidden in dialect-specific syntax) are
     * still stored so business descriptions are not lost.
     */
    private int insertParams(Connection conn, String uid, QueryModel model,
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
                    query_uid, ordinal, name, data_type, default_value,
                    required, business_label, business_description
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ParamRow r : rows) {
                ps.setString(1, uid);
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

    private List<TableInsertResult> insertTables(Connection conn, String uid, QueryModel model) throws SQLException {
        if (model.tables.isEmpty()) return List.of();

        String sql = """
                INSERT INTO query_table (
                    query_uid, raw_name, schema_resolved, table_resolved, alias, role, resolution_status
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

                ps.setString(1, uid);
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

    private int insertColumns(Connection conn, String uid, QueryModel model,
                              List<TableInsertResult> tableInserts) throws SQLException {
        if (model.columns.isEmpty()) return 0;

        String sql = """
                INSERT INTO query_column (
                    query_uid, query_table_id, schema_resolved, table_resolved, column_name, context
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

                ps.setString(1, uid);
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

    private int insertJoinPairs(Connection conn, String uid, QueryModel model) throws SQLException {
        if (model.joinPairs.isEmpty()) return 0;

        String sql = """
                INSERT INTO query_join (
                    query_uid, join_type,
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
                ps.setString(1, uid);
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

    private Map<String, Long> insertOutputs(Connection conn, String uid,
                                            List<QueryUsageOutput> outputs) throws SQLException {
        if (outputs == null || outputs.isEmpty()) return Map.of();
        Map<String, Long> aliasToId = new LinkedHashMap<>();
        String outputSql = """
                INSERT INTO query_output (
                    query_uid, alias, source_expression, business_label, business_description
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
                po.setString(1, uid);
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
                        String key = schema + "\u0000" + table + "\u0000" + column;
                        if (!uniqueColumns.add(key)) continue;
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

    private int insertFieldUsages(Connection conn, String uid, List<QueryUsageFieldUsage> usages,
                                  Map<String, Long> outputAliasToId) throws SQLException {
        if (usages == null || usages.isEmpty()) return 0;
        String sql = """
                INSERT INTO query_field_usage (
                    query_uid, query_output_id, business_object,
                    transformation_kind, transformation_description,
                    location_kind, location_details_json, headers_json, confidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (QueryUsageFieldUsage fu : usages) {
                if (fu == null) continue;
                Long outputId = fu.output() == null ? null : outputAliasToId.get(fu.output());
                ps.setString(1, uid);
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

public CatalogQueryDetail getQuery(String uid) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("uid is required");
        ensureIndexed();
        CatalogQueryDetail head = querySingle(
                "SELECT * FROM query WHERE uid = ?",
                ps -> ps.setString(1, uid),
                this::catalogQueryRow);
        if (head == null) {
            throw new IllegalArgumentException("query '" + uid + "' not found");
        }
        List<String> tags = queryList(
                "SELECT tag FROM query_tag WHERE query_uid = ? ORDER BY tag",
                ps -> ps.setString(1, uid),
                rs -> rs.getString("tag"));
        List<CatalogQueryDetail.Param> params = queryList(
                "SELECT * FROM query_param WHERE query_uid = ? ORDER BY ordinal",
                ps -> ps.setString(1, uid),
                this::paramRow);
        List<CatalogQueryDetail.Table> tables = queryList(
                "SELECT * FROM query_table WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                this::tableRow);
        List<CatalogQueryDetail.Column> columns = queryList(
                "SELECT * FROM query_column WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                this::columnRow);
        List<CatalogQueryDetail.JoinPair> joinPairs = queryList(
                "SELECT * FROM query_join WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                this::joinRow);
        List<CatalogQueryDetail.Output> outputs = queryList(
                "SELECT * FROM query_output WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                rs -> outputRow(rs));
        List<CatalogQueryDetail.FieldUsage> fieldUsages = queryList(
                "SELECT * FROM query_field_usage WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                this::fieldUsageRow);
        return new CatalogQueryDetail(
                head.uid(), head.sourceKind(), head.sourcePath(), head.sourceUnit(),
                head.businessLabel(), head.businessDomain(), head.rawSql(), head.normalizedSql(),
                head.parseStatus(), head.parseError(), head.sourceMetaJson(), head.ingestedAt(),
                tags, params, tables, columns, joinPairs, outputs, fieldUsages);
    }

public ListQueriesResult listQueries(String sourcePath, String sourceKind,
                                          String businessDomain, String tag, String parseStatus,
                                          Integer limit, Integer offset) {
        ensureIndexed();
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT q.uid, q.source_kind, q.source_path, q.source_unit,
                       q.business_label, q.business_domain, q.parse_status, q.ingested_at
                FROM query q
                """);
        List<Object> args = new ArrayList<>();
        if (tag != null && !tag.isBlank()) {
            sql.append(" JOIN query_tag t ON t.query_uid = q.uid AND t.tag = ?");
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
        sql.append(" ORDER BY q.ingested_at DESC, q.uid");
        int safeLimit = limit == null || limit <= 0 ? 100 : Math.min(limit, 1000);
        sql.append(" LIMIT ?");
        args.add(safeLimit);
        int safeOffset = offset == null || offset < 0 ? 0 : offset;
        sql.append(" OFFSET ?");
        args.add(safeOffset);

        List<ListQueriesResult.QueryEntry> entries = queryList(sql.toString(), ps -> {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
}, rs -> new ListQueriesResult.QueryEntry(
                rs.getString("uid"),
                rs.getString("source_kind"),
                rs.getString("source_path"),
                emptyToNull(rs.getString("source_unit")),
                rs.getString("business_label"),
                rs.getString("business_domain"),
                rs.getString("parse_status"),
                rs.getString("ingested_at")));

        return new ListQueriesResult(entries, safeLimit, safeOffset, entries.size());
    }

public FindQueriesByTableResult findQueriesByTable(String schema, String table) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
        ensureIndexed();
        String tableUpper = table.toUpperCase(Locale.ROOT);
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
String sql = """
                SELECT q.uid, q.source_kind, q.source_path, q.source_unit,
                       q.business_label, q.business_domain,
                       qt.role, qt.alias, qt.raw_name, qt.schema_resolved, qt.table_resolved
                FROM query_table qt
                JOIN query q ON q.uid = qt.query_uid
                WHERE qt.table_resolved = ?
                  AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL)
                ORDER BY q.uid
                """;
        List<FindQueriesByTableResult.Match> matches = queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
}, rs -> new FindQueriesByTableResult.Match(
                rs.getString("uid"),
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
                SELECT q.uid, q.source_kind, q.source_path, q.source_unit, q.business_label, q.business_domain,
                       qc.context, qc.schema_resolved, qc.table_resolved, qc.column_name
                FROM query_column qc
                JOIN query q ON q.uid = qc.query_uid
                WHERE qc.column_name = ?
                  AND (? IS NULL OR qc.table_resolved = ? OR qc.table_resolved IS NULL)
                  AND (? IS NULL OR qc.schema_resolved = ? OR qc.schema_resolved IS NULL)
                ORDER BY q.uid, qc.context
                """;
        List<FindQueriesByColumnResult.Match> matches = queryList(sql, ps -> {
            ps.setString(1, columnUpper);
            ps.setString(2, tableUpper);
            ps.setString(3, tableUpper);
            ps.setString(4, schemaUpper);
            ps.setString(5, schemaUpper);
        }, rs -> new FindQueriesByColumnResult.Match(
                rs.getString("uid"),
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
                    STRING_AGG(query_uid, '|') AS uids
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
        }, rs -> new ObservedRelationshipsResult.Relationship(
                new ObservedRelationshipsResult.SchemaRef(
                        rs.getString("left_schema"),
                        rs.getString("left_table"),
                        rs.getString("left_column")),
                new ObservedRelationshipsResult.SchemaRef(
                        rs.getString("right_schema"),
                        rs.getString("right_table"),
                        rs.getString("right_column")),
                rs.getInt("support"),
                rs.getString("uids")));
return new ObservedRelationshipsResult(schemaUpper, tableUpper, support, rels, rels.size());
    }

    public KnownTagsResult listKnownTags() {
        ensureIndexed();
        String sql = """
                SELECT t.tag, COUNT(*) AS count
                FROM query_tag t
                JOIN query q ON q.uid = t.query_uid
                GROUP BY t.tag ORDER BY count DESC, t.tag
                """;
        List<KnownTagsResult.TagEntry> entries = queryList(sql, ps -> {
        }, rs -> new KnownTagsResult.TagEntry(
                rs.getString("tag"),
                rs.getInt("count")));
        return new KnownTagsResult(entries);
    }

    /**
     * Typed bulk lookup of observed equi-join pairs for use by other services (e.g. the schema
     * context tools that build the {@code observedQuery} layer of the edge {@code evidence}
     * bundle). Unlike
     * {@link #observedRelationships}, this returns plain {@link ObservedEdge} records that are
     * cheaper to consume than untyped maps. Pass {@code tableFilter} (uppercased names) to limit
     * results; an empty/null filter returns every observed pair in the catalog.
     */
public List<ObservedEdge> observedEdges(Set<String> tableFilter, int minSupport) {
        if (!enabled()) return List.of();
        ensureIndexed();
        int support = Math.max(1, minSupport);
        String sql = """
                SELECT
                    left_schema, left_table, left_column,
                    right_schema, right_table, right_column,
                    COUNT(*) AS support,
                    STRING_AGG(query_uid, '|') AS uids
                FROM query_join
                WHERE equality = 1
                  AND left_table IS NOT NULL AND right_table IS NOT NULL
                  AND left_column IS NOT NULL AND right_column IS NOT NULL
                GROUP BY left_schema, left_table, left_column, right_schema, right_table, right_column
                HAVING COUNT(*) >= ?
                """;
        List<ObservedEdge> all = queryList(sql,
                ps -> ps.setInt(1, support),
                rs -> {
                    String uids = rs.getString("uids");
                    return new ObservedEdge(
                            rs.getString("left_schema"),
                            rs.getString("left_table"),
                            rs.getString("left_column"),
                            rs.getString("right_schema"),
                            rs.getString("right_table"),
                            rs.getString("right_column"),
                            rs.getInt("support"),
                            uids == null ? List.of() : List.of(uids.split("\\|")));
                });
        if (tableFilter == null || tableFilter.isEmpty()) return all;
        List<ObservedEdge> filtered = new ArrayList<>();
        for (ObservedEdge edge : all) {
            if (tableFilter.contains(edge.leftTable()) || tableFilter.contains(edge.rightTable())) {
                filtered.add(edge);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    /**
     * Re-resolves stored queries against the live JDBC schema using a name lookup callback. The
     * callback is invoked once per distinct unresolved table name and must return all
     * {@code (schema, name)} matches. The catalog itself never opens JDBC connections — the
     * caller (usually {@link ru.it_spectrum.ai.jdbc.mcp.tools.UsageTools}) wires this through
     * {@link ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService#findTablesByName}.
     *
     * <p>Only rows where {@code schema_resolved IS NULL} are touched; rows with an explicit
     * schema (already resolved or written that way in the SQL) are left alone. Outcomes per
     * unique raw name:
     * <ul>
     *   <li>exactly 1 match → schema written, status set to {@code resolved}.</li>
     *   <li>more than 1 match → status set to {@code ambiguous}, schema kept null.</li>
     *   <li>zero matches → status stays/becomes {@code unresolved}.</li>
     * </ul>
     * The same propagation is applied to {@code query_column} and to both sides of {@code query_join}.
     */
public ReresolveResult reresolve(NameLookup lookup) {
        ensureIndexed();
        return reresolveInternal(lookup);
    }

    private ReresolveResult reresolveInternal(NameLookup lookup) {
        if (!enabled()) {
            return new ReresolveResult(0, 0, 0, 0);
        }
        long t = System.currentTimeMillis();
        Map<String, List<String[]>> nameLookups = new LinkedHashMap<>();
        Set<String> distinctNames = collectUnresolvedTableNames();
        log.info("reresolve: {} distinct unresolved names to resolve", distinctNames.size());
        for (String name : distinctNames) {
            try {
                nameLookups.put(name, lookup.findByName(name));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Name lookup failed for '" + name + "': " + e.getMessage(), e);
            }
        }
        log.info("reresolve: name lookups done for {} names in {} ms",
                distinctNames.size(), System.currentTimeMillis() - t);

        int resolved = 0;
        int ambiguous = 0;
        int unresolved = 0;
        int totalNames = nameLookups.size();
        int processed = 0;
        long lastLog = System.currentTimeMillis();
        try (Connection conn = catalogDs.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (Map.Entry<String, List<String[]>> e : nameLookups.entrySet()) {
                    String name = e.getKey();
                    List<String[]> matches = e.getValue();
                    if (matches.size() == 1) {
                        String resolvedSchema = matches.get(0)[0];
                        String resolvedSchemaUpper = resolvedSchema == null
                                ? null : resolvedSchema.toUpperCase(Locale.ROOT);
                        applyResolution(conn, name, resolvedSchemaUpper, "resolved");
                        resolved++;
                    } else if (matches.size() > 1) {
                        applyResolution(conn, name, null, "ambiguous");
                        ambiguous++;
                    } else {
                        applyResolution(conn, name, null, "unresolved");
                        unresolved++;
                    }
                    processed++;
                    long now = System.currentTimeMillis();
                    if (processed % 200 == 0 || now - lastLog > 5000) {
                        log.info("reresolve writing: {}/{} names ({} resolved, {} ambiguous, {} unresolved, {} ms)",
                                processed, totalNames, resolved, ambiguous, unresolved, now - lastLog);
                        lastLog = now;
                    }
                }
                bumpSnapshotVersion(conn);
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Catalog write failed: " + e.getMessage(), e);
        }

        return new ReresolveResult(distinctNames.size(), resolved, ambiguous, unresolved);
    }

    private Set<String> collectUnresolvedTableNames() {
        String sql = """
                SELECT DISTINCT qt.table_resolved
                FROM query_table qt
                JOIN query q ON q.uid = qt.query_uid
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

    private void bumpSnapshotVersion(Connection conn) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query SET resolved_snapshot_version = ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }
    }

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

    /**
     * Builds a typed projection of usage-catalog evidence for one physical table. This is the
     * bridge from stored application/report queries back into schema-context responses:
     * {@code observedQuery} describes how the table/columns are referenced, while
     * {@code semanticUsage} carries business labels, domains, tags and field usages.
     */
public TableEvidenceProfile tableEvidenceProfile(String schema, String table) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
        ensureIndexed();
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
        String tableUpper = table.toUpperCase(Locale.ROOT);
        List<String> queryUids = tableQueryUids(schemaUpper, tableUpper);
        ObservedTableUsage observed = new ObservedTableUsage(
                queryUids.size(),
                capStrings(queryUids, QUERY_UID_PREVIEW_LIMIT),
                observedColumnUsages(schemaUpper, tableUpper));
        SemanticTableUsage semantic = new SemanticTableUsage(
                tableQueryTerms(schemaUpper, tableUpper, "q.business_domain", "query_table qt", null),
                tableQueryTerms(schemaUpper, tableUpper, "tag.tag", "query_table qt JOIN query_tag tag ON tag.query_uid = qt.query_uid", null),
                tableQueryTerms(schemaUpper, tableUpper, "q.business_label", "query_table qt", null),
                outputTerms(schemaUpper, tableUpper, "qo.business_label", null),
                outputTerms(schemaUpper, tableUpper, "qfu.business_object",
                        "JOIN query_field_usage qfu ON qfu.query_output_id = qoc.query_output_id"),
                semanticColumnUsages(schemaUpper, tableUpper));
        return new TableEvidenceProfile(schemaUpper, tableUpper, observed, semantic);
    }

    /**
     * Computes the semantic layer of evidence for a relationship between two physical tables.
     * The evidence is restricted to <i>queries that touch both tables</i> (resolved
     * {@code query_table} rows). For that co-occurring query set we report:
     * <ul>
     *   <li>shared business domains ({@code query.business_domain}),</li>
     *   <li>shared business objects ({@code query_field_usage.business_object}),</li>
     *   <li>shared output labels ({@code query_output.business_label}),</li>
     *   <li>the count and a capped uid preview of the co-occurring queries themselves.</li>
     * </ul>
     * This intentionally does <b>not</b> propose new relationships — semantic evidence here is a
     * decoration on edges that already exist via declared FKs or observed equi-joins.
     * Returns {@code null} when the catalog is disabled or table inputs are blank.
     */
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

        List<String> coUids = coOccurringQueryUids(
                leftSchemaUpper, leftTableUpper, rightSchemaUpper, rightTableUpper);
        if (coUids.isEmpty()) {
            return new SemanticEdgeEvidence(List.of(), List.of(), List.of(), 0, List.of());
        }
        return new SemanticEdgeEvidence(
                sharedTerms(coUids, "q.business_domain", "query q",
                        "q.business_domain IS NOT NULL AND q.business_domain <> ''", "q.uid"),
                sharedTerms(coUids, "qfu.business_object", "query_field_usage qfu",
                        "qfu.business_object IS NOT NULL AND qfu.business_object <> ''", "qfu.query_uid"),
                sharedTerms(coUids, "qo.business_label", "query_output qo",
                        "qo.business_label IS NOT NULL AND qo.business_label <> ''", "qo.query_uid"),
                coUids.size(),
                capStrings(coUids, QUERY_UID_PREVIEW_LIMIT));
    }

    private List<String> coOccurringQueryUids(String leftSchemaUpper, String leftTableUpper,
                                              String rightSchemaUpper, String rightTableUpper) {
        String sql = """
                SELECT DISTINCT q.uid
                FROM query q
                WHERE EXISTS (SELECT 1 FROM query_table qt WHERE qt.query_uid = q.uid
                              AND qt.table_resolved = ?
                              AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL))
                  AND EXISTS (SELECT 1 FROM query_table qt WHERE qt.query_uid = q.uid
                              AND qt.table_resolved = ?
                              AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL))
                ORDER BY q.uid
                """;
        return queryList(sql, ps -> {
            ps.setString(1, leftTableUpper);
            ps.setString(2, leftSchemaUpper);
            ps.setString(3, leftSchemaUpper);
            ps.setString(4, rightTableUpper);
            ps.setString(5, rightSchemaUpper);
            ps.setString(6, rightSchemaUpper);
        }, rs -> rs.getString("uid"));
    }

    private List<SemanticTermEvidence> sharedTerms(List<String> queryUids,
                                                   String valueExpression,
                                                   String fromExpression,
                                                   String whereExtra,
                                                   String uidColumn) {
        if (queryUids == null || queryUids.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(queryUids.size(), "?"));
        String sql = ("""
                SELECT %s AS term_value,
                       COUNT(DISTINCT %s) AS support,
                       STRING_AGG(%s, '|') AS uids
                FROM %s
                WHERE %s
                  AND %s IN (%s)
                GROUP BY %s
                ORDER BY support DESC, term_value
                LIMIT ?
                """).formatted(valueExpression, uidColumn, uidColumn, fromExpression,
                whereExtra, uidColumn, placeholders, valueExpression);
        return queryList(sql, ps -> {
            int i = 1;
            for (String uid : queryUids) ps.setString(i++, uid);
            ps.setInt(i, DEFAULT_PROFILE_LIMIT);
        }, this::termEvidenceRow);
    }

    /**
     * Finds physical tables whose cataloged queries carry semantic terms matching the user's
     * natural-language terms. This is deliberately a usage-catalog lookup: live database metadata
     * remains the authority for whether the candidate table can actually be described.
     */
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
                       COUNT(DISTINCT query_uid) AS support,
                       STRING_AGG(query_uid, '|') AS uids
                FROM (
                    SELECT qt.schema_resolved AS schema_name, qt.table_resolved AS table_name,
                           'business_domain' AS source_kind, q.business_domain AS term_value, q.uid AS query_uid
                    FROM query_table qt
                    JOIN query q ON q.uid = qt.query_uid
                    WHERE q.business_domain IS NOT NULL AND q.business_domain <> ''
                    UNION ALL
                    SELECT qt.schema_resolved AS schema_name, qt.table_resolved AS table_name,
                           'business_tag' AS source_kind, tag.tag AS term_value, q.uid AS query_uid
                    FROM query_table qt
                    JOIN query q ON q.uid = qt.query_uid
                    JOIN query_tag tag ON tag.query_uid = q.uid
                    UNION ALL
                    SELECT qt.schema_resolved AS schema_name, qt.table_resolved AS table_name,
                           'query_label' AS source_kind, q.business_label AS term_value, q.uid AS query_uid
                    FROM query_table qt
                    JOIN query q ON q.uid = qt.query_uid
                    WHERE q.business_label IS NOT NULL AND q.business_label <> ''
                    UNION ALL
                    SELECT qoc.schema_resolved AS schema_name, qoc.table_resolved AS table_name,
                           'output_label' AS source_kind, qo.business_label AS term_value, qo.query_uid AS query_uid
                    FROM query_output_column qoc
                    JOIN query_output qo ON qo.id = qoc.query_output_id
                    WHERE qo.business_label IS NOT NULL AND qo.business_label <> ''
                    UNION ALL
                    SELECT qoc.schema_resolved AS schema_name, qoc.table_resolved AS table_name,
                           'business_object' AS source_kind, qfu.business_object AS term_value, qo.query_uid AS query_uid
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
                splitUids(rs.getString("uids"))));
        return aggregateSemanticCandidates(rows, safeLimit);
    }

    private List<SemanticTableCandidate> aggregateSemanticCandidates(List<SemanticTermRow> rows, int limit) {
        Map<String, SemanticCandidateAccumulator> byTable = new LinkedHashMap<>();
        for (SemanticTermRow row : rows) {
            String key = (row.schema() == null ? "" : row.schema()) + "\u0000" + row.table();
            SemanticCandidateAccumulator acc = byTable.computeIfAbsent(key,
                    ignored -> new SemanticCandidateAccumulator(row.schema(), row.table()));
            acc.support += row.support();
            acc.queryUids.addAll(row.queryUids());
            acc.terms.add(new SemanticTermEvidence(
                    row.sourceKind() + ":" + row.termValue(),
                    row.support(),
                    capStrings(row.queryUids(), QUERY_UID_PREVIEW_LIMIT)));
        }
        return byTable.values().stream()
                .map(acc -> new SemanticTableCandidate(
                        acc.schema,
                        acc.table,
                        acc.support,
                        capTerms(acc.terms, DEFAULT_PROFILE_LIMIT),
                        capStrings(new ArrayList<>(acc.queryUids), QUERY_UID_PREVIEW_LIMIT)))
                .sorted((a, b) -> {
                    int bySupport = Integer.compare(b.support(), a.support());
                    return bySupport != 0 ? bySupport : a.table().compareToIgnoreCase(b.table());
                })
                .limit(limit)
                .toList();
    }

    private List<String> tableQueryUids(String schemaUpper, String tableUpper) {
        String sql = """
                SELECT DISTINCT q.uid
                FROM query_table qt
                JOIN query q ON q.uid = qt.query_uid
                WHERE qt.table_resolved = ?
                  AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL)
                ORDER BY q.uid
                """;
        return queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
        }, rs -> rs.getString("uid"));
    }

    private List<ObservedColumnUsage> observedColumnUsages(String schemaUpper, String tableUpper) {
        String sql = """
                SELECT qc.column_name, qc.context,
                       COUNT(DISTINCT qc.query_uid) AS support,
                       STRING_AGG(qc.query_uid, '|') AS uids
                FROM query_column qc
                WHERE qc.table_resolved = ?
                  AND (? IS NULL OR qc.schema_resolved = ? OR qc.schema_resolved IS NULL)
                GROUP BY qc.column_name, qc.context
                ORDER BY qc.column_name, support DESC, qc.context
                """;
        Map<String, ColumnUsageAccumulator> byColumn = new LinkedHashMap<>();
        List<Map<String, Object>> rows = queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
        }, rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("column", rs.getString("column_name"));
            row.put("context", rs.getString("context"));
            row.put("support", rs.getInt("support"));
            row.put("uids", rs.getString("uids"));
            return row;
        });
        for (Map<String, Object> row : rows) {
            String column = stringValue(row.get("column"));
            if (column == null) continue;
            ColumnUsageAccumulator acc = byColumn.computeIfAbsent(column, ColumnUsageAccumulator::new);
            List<String> uids = splitUids(stringValue(row.get("uids")));
            acc.queryUids.addAll(uids);
            acc.contexts.add(new SemanticTermEvidence(
                    stringValue(row.get("context")),
                    ((Number) row.get("support")).intValue(),
                    capStrings(uids, QUERY_UID_PREVIEW_LIMIT)));
        }
        return byColumn.values().stream()
                .map(acc -> new ObservedColumnUsage(
                        acc.column,
                        acc.queryUids.size(),
                        acc.contexts,
                        capStrings(new ArrayList<>(acc.queryUids), QUERY_UID_PREVIEW_LIMIT)))
                .toList();
    }

    private List<SemanticColumnUsage> semanticColumnUsages(String schemaUpper, String tableUpper) {
        Map<String, SemanticColumnAccumulator> byColumn = new LinkedHashMap<>();
        for (ColumnTerm term : outputColumnTerms(schemaUpper, tableUpper, "qo.business_label", null)) {
            byColumn.computeIfAbsent(term.column(), SemanticColumnAccumulator::new)
                    .outputLabels.add(term.evidence());
        }
        for (ColumnTerm term : outputColumnTerms(schemaUpper, tableUpper, "qfu.business_object",
                "JOIN query_field_usage qfu ON qfu.query_output_id = qoc.query_output_id")) {
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
                       COUNT(DISTINCT q.uid) AS support,
                       STRING_AGG(q.uid, '|') AS uids
                FROM %s
                JOIN query q ON q.uid = qt.query_uid%s
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
                       COUNT(DISTINCT qo.query_uid) AS support,
                       STRING_AGG(qo.query_uid, '|') AS uids
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
                       COUNT(DISTINCT qo.query_uid) AS support,
                       STRING_AGG(qo.query_uid, '|') AS uids
                FROM query_output_column qoc
                JOIN query_output qo ON qo.id = qoc.query_output_id%s
                WHERE qoc.table_resolved = ?
                  AND (? IS NULL OR qoc.schema_resolved = ? OR qoc.schema_resolved IS NULL)
                  AND %s IS NOT NULL AND %s <> ''
                GROUP BY qoc.column_name, %s
                ORDER BY qoc.column_name, support DESC, term_value
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

private CatalogQueryDetail catalogQueryRow(ResultSet rs) throws SQLException {
        return new CatalogQueryDetail(
                rs.getString("uid"),
                rs.getString("source_kind"),
                rs.getString("source_path"),
                emptyToNull(rs.getString("source_unit")),
                rs.getString("business_label"),
                rs.getString("business_domain"),
                rs.getString("raw_sql"),
                rs.getString("normalized_sql"),
                rs.getString("parse_status"),
                rs.getString("parse_error"),
                rs.getString("source_meta_json"),
                rs.getString("ingested_at"),
                null, null, null, null, null, null, null
        );
    }

    private CatalogQueryDetail.Param paramRow(ResultSet rs) throws SQLException {
        return new CatalogQueryDetail.Param(
                rs.getInt("ordinal"),
                rs.getString("name"),
                rs.getString("data_type"),
                rs.getString("default_value"),
                rs.getInt("required") != 0,
                rs.getString("business_label"),
                rs.getString("business_description"));
    }

    private CatalogQueryDetail.Table tableRow(ResultSet rs) throws SQLException {
        return new CatalogQueryDetail.Table(
                rs.getString("raw_name"),
                rs.getString("schema_resolved"),
                rs.getString("table_resolved"),
                rs.getString("alias"),
                rs.getString("role"),
                rs.getString("resolution_status"));
    }

    private CatalogQueryDetail.Column columnRow(ResultSet rs) throws SQLException {
        return new CatalogQueryDetail.Column(
                rs.getString("schema_resolved"),
                rs.getString("table_resolved"),
                rs.getString("column_name"),
                rs.getString("context"));
    }

    private CatalogQueryDetail.JoinPair joinRow(ResultSet rs) throws SQLException {
        return new CatalogQueryDetail.JoinPair(
                rs.getString("join_type"),
                new CatalogQueryDetail.JoinPair.SchemaRef(
                        rs.getString("left_schema"),
                        rs.getString("left_table"),
                        rs.getString("left_column")),
                new CatalogQueryDetail.JoinPair.SchemaRef(
                        rs.getString("right_schema"),
                        rs.getString("right_table"),
                        rs.getString("right_column")),
                rs.getString("on_text"),
                rs.getInt("equality") != 0);
    }

    private CatalogQueryDetail.Output outputRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        List<CatalogQueryDetail.Output.DerivedColumn> derived = queryList(
                "SELECT * FROM query_output_column WHERE query_output_id = ?",
                ps -> ps.setLong(1, id),
                cs -> new CatalogQueryDetail.Output.DerivedColumn(
                        cs.getString("schema_resolved"),
                        cs.getString("table_resolved"),
                        cs.getString("column_name")));
        return new CatalogQueryDetail.Output(
                rs.getString("alias"),
                rs.getString("source_expression"),
                rs.getString("business_label"),
                rs.getString("business_description"),
                derived);
    }

    private CatalogQueryDetail.FieldUsage fieldUsageRow(ResultSet rs) throws SQLException {
        return new CatalogQueryDetail.FieldUsage(
                rs.getObject("query_output_id", Long.class),
                rs.getString("business_object"),
                new CatalogQueryDetail.FieldUsage.Transformation(
                        rs.getString("transformation_kind"),
                        rs.getString("transformation_description")),
                new CatalogQueryDetail.FieldUsage.Location(
                        rs.getString("location_kind"),
                        rs.getString("location_details_json")),
                rs.getString("headers_json"),
rs.getString("confidence"));
    }

    private SemanticTermEvidence termEvidenceRow(ResultSet rs) throws SQLException {
        return new SemanticTermEvidence(
                rs.getString("term_value"),
                rs.getInt("support"),
                capStrings(splitUids(rs.getString("uids")), QUERY_UID_PREVIEW_LIMIT));
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
        Long byTable = null;
        for (TableInsertResult t : tables) {
            if (qualifier.equalsIgnoreCase(t.alias) && byAlias == null) byAlias = t.id;
            if (resolved.table.equalsIgnoreCase(t.tableResolved) && byTable == null) byTable = t.id;
        }
        return byAlias != null ? byAlias : byTable;
    }

    private static QueryUsageParameter findParamByName(List<QueryUsageParameter> params, String name) {
        if (params == null) return null;
        for (QueryUsageParameter p : params) {
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

    private static List<String> splitUids(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String uid : raw.split("\\|")) {
            if (uid == null || uid.isBlank()) continue;
            if (seen.add(uid)) out.add(uid);
        }
        return out;
    }

    private static List<String> capStrings(List<String> values, int limit) {
        if (values == null || values.isEmpty()) return List.of();
        int safeLimit = Math.max(0, limit);
        if (values.size() <= safeLimit) return List.copyOf(values);
        return List.copyOf(values.subList(0, safeLimit));
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

    private record TableInsertResult(long id, String rawName, String alias,
                                     String schemaResolved, String tableResolved) {
    }

    private record ColumnTerm(String column, SemanticTermEvidence evidence) {
    }

    private record LoadResult(List<QueryUsage> records, int filesScanned,
                              List<String> errors, List<String> duplicateUids) {
    }

    private record SemanticTermRow(String schema, String table, String sourceKind,
                                   String termValue, int support, List<String> queryUids) {
    }

    private static class ColumnUsageAccumulator {
        final String column;
        final Set<String> queryUids = new LinkedHashSet<>();
        final List<SemanticTermEvidence> contexts = new ArrayList<>();

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
        final Set<String> queryUids = new LinkedHashSet<>();
        final List<SemanticTermEvidence> terms = new ArrayList<>();

        SemanticCandidateAccumulator(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }
    }

    /**
     * One observed equi-join pair aggregated across stored queries. Schema columns are nullable
     * because some queries write unqualified table references that have not (yet) been resolved
     * by {@link #reresolve}.
     */
    public record ObservedEdge(
            String leftSchema, String leftTable, String leftColumn,
            String rightSchema, String rightTable, String rightColumn,
            int support, List<String> queryUids
    ) {
    }

    /**
     * Strategy interface used by {@link #reresolve} to look up a table name in the inspected
     * database without coupling the catalog to the live JDBC layer. Implementations should
     * return one {@code [schema, name]} pair per matching object across all schemas.
     */
    @FunctionalInterface
    public interface NameLookup {
        List<String[]> findByName(String tableName) throws Exception;
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
}
