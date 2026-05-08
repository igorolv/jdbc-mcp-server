package ru.it_spectrum.ai.jdbc.mcp.usage;

import net.sf.jsqlparser.JSQLParserException;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.ObservedColumnUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.ObservedTableUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticColumnUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTermEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.TableEvidenceProfile;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService.QueryModel;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonWriter;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageFieldUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageOutput;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageOutputColumn;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageParameter;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;

import javax.sql.DataSource;
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
 * <p>Resolution strategy (Phase 1): table and column qualifiers are resolved via the parser's
 * alias map and uppercased for case-insensitive matching. Resolution against the live JDBC schema
 * snapshot is deferred to a future {@code reresolveQueries} tool.
 */
@Service
public class UsageCatalogService {

    private static final int DEFAULT_PROFILE_LIMIT = 10;
    private static final int QUERY_UID_PREVIEW_LIMIT = 20;

    private final UsageProperties properties;
    private final DataSource catalogDs;
    private final QueryAnalysisService analysis;

    public UsageCatalogService(UsageProperties properties,
                               DataSource usageDataSource,
                               QueryAnalysisService analysis) {
        this.properties = properties;
        this.catalogDs = usageDataSource;
        this.analysis = analysis;
    }

    public boolean enabled() {
        return properties.catalogEnabled();
    }

    // ---------------------------------------------------------------------------------------
    //  Rebuild
    // ---------------------------------------------------------------------------------------

    public Map<String, Object> rebuild(List<QueryUsage> records) {
        if (!enabled()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("catalog_enabled", false);
            body.put("recordsLoaded", 0);
            return body;
        }
        List<QueryUsage> safeRecords = records == null ? List.of() : records;
        Counters counters = new Counters();
        int parseFailed = 0;
        Set<String> seen = new LinkedHashSet<>();
        long started = System.currentTimeMillis();
        try (Connection conn = catalogDs.getConnection()) {
            conn.setAutoCommit(false);
            try {
                clearAll(conn);
                for (QueryUsage req : safeRecords) {
                    validateRequest(req);
                    QueryUsageSource src = req.source();
                    String unit = src.unit() == null ? "" : src.unit();
                    String uid = UsageUid.build(req.dataSource(), src.path(), unit);
                    if (!seen.add(uid)) {
                        throw new IllegalArgumentException("duplicate query uid: " + uid);
                    }

                    QueryModel model;
                    String parseStatus;
                    String parseError = null;
                    try {
                        model = analysis.model(req.sql());
                        parseStatus = "parsed";
                    } catch (JSQLParserException | RuntimeException e) {
                        model = new QueryModel();
                        parseStatus = "failed";
                        parseError = rootMessage(e);
                        parseFailed++;
                    }
                    insertAnalyzed(conn, uid, req, src, unit, model, parseStatus, parseError, counters);
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to rebuild usage catalog index: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordsLoaded", safeRecords.size());
        out.put("parseFailed", parseFailed);
        out.put("paramsStored", counters.paramsStored);
        out.put("tablesExtracted", counters.tablesExtracted);
        out.put("columnsExtracted", counters.columnsExtracted);
        out.put("joinPairsExtracted", counters.joinPairsExtracted);
        out.put("outputsStored", counters.outputsStored);
        out.put("fieldUsagesStored", counters.fieldUsagesStored);
        out.put("indexBuildMs", System.currentTimeMillis() - started);
        return out;
    }

    private void validateRequest(QueryUsage req) {
        if (req == null) throw new IllegalArgumentException("payload is required");
        if (req.schemaVersion() == null || req.schemaVersion() != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (req.dataSource() == null || req.dataSource().isBlank()) {
            throw new IllegalArgumentException("dataSource is required");
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
        UsageUid.validate(req.dataSource(), req.source().path(), req.source().unit());

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
                    uid, data_source, source_kind, source_path, source_unit,
                    business_label, business_domain, raw_sql, normalized_sql,
                    parse_status, parse_error, source_meta_json, ingested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uid);
            ps.setString(2, req.dataSource());
            ps.setString(3, src.kind());
            ps.setString(4, src.path());
            ps.setString(5, unit);
            ps.setString(6, req.businessLabel());
            ps.setString(7, req.businessDomain());
            ps.setString(8, req.sql());
            ps.setString(9, model.normalizedSql);
            ps.setString(10, parseStatus);
            ps.setString(11, parseError);
            ps.setString(12, req.sourceMeta() == null ? null : JsonWriter.write(req.sourceMeta()));
            ps.setString(13, Instant.now().toString());
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

        for (Map<String, Object> parsed : model.parameters) {
            ordinal++;
            String name = stringValue(parsed.get("name"));
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
            for (Map<String, Object> table : model.tables) {
                String rawName = stringValue(table.get("name"));
                String schema = stringValue(table.get("schema"));
                String alias = stringValue(table.get("alias"));
                String role = stringValue(table.get("source"));
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
            for (Map<String, Object> col : model.columns) {
                String columnName = stringValue(col.get("name"));
                if (columnName == null) continue;
                String qualifier = stringValue(col.get("qualifier"));
                String context = stringValue(col.get("context"));
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
                        ? null : JsonWriter.write(fu.location().details()));
                ps.setString(8, fu.headers() == null ? null : JsonWriter.write(fu.headers()));
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

    public Map<String, Object> getQuery(String uid) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("uid is required");
        Map<String, Object> head = querySingle(
                "SELECT * FROM query WHERE uid = ?",
                ps -> ps.setString(1, uid),
                this::queryRow);
        if (head == null) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("error", "query '" + uid + "' not found");
            notFound.put("kind", "not_found");
            notFound.put("missing", "query");
            notFound.put("name", uid);
            return notFound;
        }
        head.put("tags", queryList(
                "SELECT tag FROM query_tag WHERE query_uid = ? ORDER BY tag",
                ps -> ps.setString(1, uid),
                rs -> rs.getString("tag")));
        head.put("parameters", queryList(
                "SELECT * FROM query_param WHERE query_uid = ? ORDER BY ordinal",
                ps -> ps.setString(1, uid),
                this::paramRow));
        head.put("tables", queryList(
                "SELECT * FROM query_table WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                this::tableRow));
        head.put("columns", queryList(
                "SELECT * FROM query_column WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                this::columnRow));
        head.put("joinPairs", queryList(
                "SELECT * FROM query_join WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                this::joinRow));
        head.put("outputs", queryList(
                "SELECT * FROM query_output WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                rs -> outputRow(rs, uid)));
        head.put("fieldUsages", queryList(
                "SELECT * FROM query_field_usage WHERE query_uid = ? ORDER BY id",
                ps -> ps.setString(1, uid),
                this::fieldUsageRow));
        return head;
    }

    public Map<String, Object> listQueries(String dataSource, String sourcePath, String sourceKind,
                                            String businessDomain, String tag, String parseStatus,
                                            Integer limit, Integer offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT q.uid, q.data_source, q.source_kind, q.source_path, q.source_unit,
                       q.business_label, q.business_domain, q.parse_status, q.ingested_at
                FROM query q
                """);
        List<Object> args = new ArrayList<>();
        if (tag != null && !tag.isBlank()) {
            sql.append(" JOIN query_tag t ON t.query_uid = q.uid AND t.tag = ?");
            args.add(tag);
        }
        sql.append(" WHERE 1=1");
        if (dataSource != null && !dataSource.isBlank()) {
            sql.append(" AND q.data_source = ?");
            args.add(dataSource);
        }
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

        List<Map<String, Object>> rows = queryList(sql.toString(), ps -> {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
        }, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("uid", rs.getString("uid"));
            r.put("dataSource", rs.getString("data_source"));
            r.put("sourceKind", rs.getString("source_kind"));
            r.put("sourcePath", rs.getString("source_path"));
            r.put("sourceUnit", emptyToNull(rs.getString("source_unit")));
            r.put("businessLabel", rs.getString("business_label"));
            r.put("businessDomain", rs.getString("business_domain"));
            r.put("parseStatus", rs.getString("parse_status"));
            r.put("ingestedAt", rs.getString("ingested_at"));
            return r;
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queries", rows);
        out.put("limit", safeLimit);
        out.put("offset", safeOffset);
        out.put("count", rows.size());
        return out;
    }

    public Map<String, Object> findQueriesByTable(String schema, String table) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
        String tableUpper = table.toUpperCase(Locale.ROOT);
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
        String sql = """
                SELECT q.uid, q.data_source, q.source_kind, q.source_path, q.source_unit,
                       q.business_label, q.business_domain,
                       qt.role, qt.alias, qt.raw_name, qt.schema_resolved, qt.table_resolved
                FROM query_table qt
                JOIN query q ON q.uid = qt.query_uid
                WHERE qt.table_resolved = ?
                  AND (? IS NULL OR qt.schema_resolved = ? OR qt.schema_resolved IS NULL)
                ORDER BY q.uid
                """;
        List<Map<String, Object>> rows = queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, schemaUpper);
            ps.setString(3, schemaUpper);
        }, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("uid", rs.getString("uid"));
            r.put("dataSource", rs.getString("data_source"));
            r.put("sourceKind", rs.getString("source_kind"));
            r.put("sourcePath", rs.getString("source_path"));
            r.put("sourceUnit", emptyToNull(rs.getString("source_unit")));
            r.put("businessLabel", rs.getString("business_label"));
            r.put("businessDomain", rs.getString("business_domain"));
            r.put("role", rs.getString("role"));
            r.put("alias", rs.getString("alias"));
            r.put("rawName", rs.getString("raw_name"));
            r.put("schemaResolved", rs.getString("schema_resolved"));
            r.put("tableResolved", rs.getString("table_resolved"));
            return r;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schemaUpper);
        out.put("table", tableUpper);
        out.put("matches", rows);
        out.put("count", rows.size());
        return out;
    }

    public Map<String, Object> findQueriesByColumn(String schema, String table, String column) {
        if (column == null || column.isBlank()) throw new IllegalArgumentException("column is required");
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
        List<Map<String, Object>> rows = queryList(sql, ps -> {
            ps.setString(1, columnUpper);
            ps.setString(2, tableUpper);
            ps.setString(3, tableUpper);
            ps.setString(4, schemaUpper);
            ps.setString(5, schemaUpper);
        }, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("uid", rs.getString("uid"));
            r.put("sourceKind", rs.getString("source_kind"));
            r.put("sourcePath", rs.getString("source_path"));
            r.put("sourceUnit", emptyToNull(rs.getString("source_unit")));
            r.put("businessLabel", rs.getString("business_label"));
            r.put("businessDomain", rs.getString("business_domain"));
            r.put("context", rs.getString("context"));
            r.put("schemaResolved", rs.getString("schema_resolved"));
            r.put("tableResolved", rs.getString("table_resolved"));
            r.put("columnName", rs.getString("column_name"));
            return r;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schemaUpper);
        out.put("table", tableUpper);
        out.put("column", columnUpper);
        out.put("matches", rows);
        out.put("count", rows.size());
        return out;
    }

    public Map<String, Object> observedRelationships(String schema, String table, int minSupport) {
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
        List<Map<String, Object>> rows = queryList(sql, ps -> {
            ps.setString(1, tableUpper);
            ps.setString(2, tableUpper);
            ps.setString(3, tableUpper);
            ps.setString(4, schemaUpper);
            ps.setString(5, schemaUpper);
            ps.setString(6, schemaUpper);
            ps.setInt(7, support);
        }, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            Map<String, Object> left = new LinkedHashMap<>();
            left.put("schema", rs.getString("left_schema"));
            left.put("table", rs.getString("left_table"));
            left.put("column", rs.getString("left_column"));
            Map<String, Object> right = new LinkedHashMap<>();
            right.put("schema", rs.getString("right_schema"));
            right.put("table", rs.getString("right_table"));
            right.put("column", rs.getString("right_column"));
            r.put("left", left);
            r.put("right", right);
            r.put("support", rs.getInt("support"));
            String uids = rs.getString("uids");
            r.put("queries", uids == null ? List.of() : List.of(uids.split("\\|")));
            return r;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schemaUpper);
        out.put("table", tableUpper);
        out.put("minSupport", support);
        out.put("relationships", rows);
        out.put("count", rows.size());
        return out;
    }

    public Map<String, Object> listKnownTags(String dataSource) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.tag, COUNT(*) AS count
                FROM query_tag t
                JOIN query q ON q.uid = t.query_uid
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (dataSource != null && !dataSource.isBlank()) {
            sql.append(" AND q.data_source = ?");
            args.add(dataSource);
        }
        sql.append(" GROUP BY t.tag ORDER BY count DESC, t.tag");
        List<Map<String, Object>> rows = queryList(sql.toString(), ps -> {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
        }, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tag", rs.getString("tag"));
            r.put("count", rs.getInt("count"));
            return r;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataSource", dataSource);
        out.put("tags", rows);
        return out;
    }

    /**
     * Typed bulk lookup of observed equi-join pairs for use by other services (e.g. the schema
     * context tools that decorate edges with {@code evidenceLevel}). Unlike
     * {@link #observedRelationships}, this returns plain {@link ObservedEdge} records that are
     * cheaper to consume than untyped maps. Pass {@code tableFilter} (uppercased names) to limit
     * results; an empty/null filter returns every observed pair in the catalog.
     */
    public List<ObservedEdge> observedEdges(Set<String> tableFilter, int minSupport) {
        if (!enabled()) return List.of();
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
    public Map<String, Object> reresolve(String dataSource, NameLookup lookup) {
        if (!enabled()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("catalog_enabled", false);
            body.put("resolved", 0);
            return body;
        }
        Map<String, List<String[]>> nameLookups = new LinkedHashMap<>();
        Set<String> distinctNames = collectUnresolvedTableNames(dataSource);
        for (String name : distinctNames) {
            try {
                nameLookups.put(name, lookup.findByName(name));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Name lookup failed for '" + name + "': " + e.getMessage(), e);
            }
        }

        int resolved = 0;
        int ambiguous = 0;
        int unresolved = 0;
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
                        applyResolution(conn, dataSource, name, resolvedSchemaUpper, "resolved");
                        resolved++;
                    } else if (matches.size() > 1) {
                        applyResolution(conn, dataSource, name, null, "ambiguous");
                        ambiguous++;
                    } else {
                        applyResolution(conn, dataSource, name, null, "unresolved");
                        unresolved++;
                    }
                }
                bumpSnapshotVersion(conn, dataSource);
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Catalog write failed: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataSource", dataSource);
        out.put("namesLookedUp", distinctNames.size());
        out.put("tablesResolved", resolved);
        out.put("tablesAmbiguous", ambiguous);
        out.put("tablesUnresolved", unresolved);
        return out;
    }

    private Set<String> collectUnresolvedTableNames(String dataSource) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT qt.table_resolved
                FROM query_table qt
                JOIN query q ON q.uid = qt.query_uid
                WHERE qt.table_resolved IS NOT NULL
                  AND qt.schema_resolved IS NULL
                  AND qt.resolution_status <> 'cte'
                """);
        if (dataSource != null && !dataSource.isBlank()) sql.append(" AND q.data_source = ?");
        return new LinkedHashSet<>(queryList(sql.toString(), ps -> {
            if (dataSource != null && !dataSource.isBlank()) ps.setString(1, dataSource);
        }, rs -> rs.getString(1)));
    }

    private void applyResolution(Connection conn, String dataSource, String tableUpper,
                                 String schemaUpper, String status) throws SQLException {
        String dsClause = dataSource != null && !dataSource.isBlank()
                ? " AND query_uid IN (SELECT uid FROM query WHERE data_source = ?)" : "";

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query_table SET schema_resolved = ?, resolution_status = ?"
                        + " WHERE table_resolved = ? AND schema_resolved IS NULL"
                        + " AND resolution_status <> 'cte'" + dsClause)) {
            int i = 1;
            ps.setString(i++, schemaUpper);
            ps.setString(i++, status);
            ps.setString(i++, tableUpper);
            if (!dsClause.isEmpty()) ps.setString(i, dataSource);
            ps.executeUpdate();
        }
        if (!"resolved".equals(status)) return;

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query_column SET schema_resolved = ?"
                        + " WHERE table_resolved = ? AND schema_resolved IS NULL" + dsClause)) {
            int i = 1;
            ps.setString(i++, schemaUpper);
            ps.setString(i++, tableUpper);
            if (!dsClause.isEmpty()) ps.setString(i, dataSource);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query_join SET left_schema = ?"
                        + " WHERE left_table = ? AND left_schema IS NULL" + dsClause)) {
            int i = 1;
            ps.setString(i++, schemaUpper);
            ps.setString(i++, tableUpper);
            if (!dsClause.isEmpty()) ps.setString(i, dataSource);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query_join SET right_schema = ?"
                        + " WHERE right_table = ? AND right_schema IS NULL" + dsClause)) {
            int i = 1;
            ps.setString(i++, schemaUpper);
            ps.setString(i++, tableUpper);
            if (!dsClause.isEmpty()) ps.setString(i, dataSource);
            ps.executeUpdate();
        }
    }

    private void bumpSnapshotVersion(Connection conn, String dataSource) throws SQLException {
        long now = System.currentTimeMillis();
        String dsClause = dataSource != null && !dataSource.isBlank() ? " WHERE data_source = ?" : "";
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE query SET resolved_snapshot_version = ?" + dsClause)) {
            ps.setLong(1, now);
            if (!dsClause.isEmpty()) ps.setString(2, dataSource);
            ps.executeUpdate();
        }
    }

    public Map<String, Object> listKnownDomains(String dataSource) {
        StringBuilder sql = new StringBuilder("""
                SELECT business_domain, COUNT(*) AS count
                FROM query
                WHERE business_domain IS NOT NULL
                """);
        List<Object> args = new ArrayList<>();
        if (dataSource != null && !dataSource.isBlank()) {
            sql.append(" AND data_source = ?");
            args.add(dataSource);
        }
        sql.append(" GROUP BY business_domain ORDER BY count DESC, business_domain");
        List<Map<String, Object>> rows = queryList(sql.toString(), ps -> {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
        }, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("domain", rs.getString("business_domain"));
            r.put("count", rs.getInt("count"));
            return r;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataSource", dataSource);
        out.put("domains", rows);
        return out;
    }

    /**
     * Builds a typed projection of usage-catalog evidence for one physical table. This is the
     * bridge from stored application/report queries back into schema-context responses:
     * {@code observedQuery} describes how the table/columns are referenced, while
     * {@code semanticUsage} carries business labels, domains, tags and field usages.
     */
    public TableEvidenceProfile tableEvidenceProfile(String schema, String table) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
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

    private Map<String, Object> queryRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("uid", rs.getString("uid"));
        row.put("dataSource", rs.getString("data_source"));
        row.put("sourceKind", rs.getString("source_kind"));
        row.put("sourcePath", rs.getString("source_path"));
        row.put("sourceUnit", emptyToNull(rs.getString("source_unit")));
        row.put("businessLabel", rs.getString("business_label"));
        row.put("businessDomain", rs.getString("business_domain"));
        row.put("rawSql", rs.getString("raw_sql"));
        row.put("normalizedSql", rs.getString("normalized_sql"));
        row.put("parseStatus", rs.getString("parse_status"));
        row.put("parseError", rs.getString("parse_error"));
        row.put("sourceMetaJson", rs.getString("source_meta_json"));
        row.put("ingestedAt", rs.getString("ingested_at"));
        return row;
    }

    private Map<String, Object> paramRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ordinal", rs.getInt("ordinal"));
        row.put("name", rs.getString("name"));
        row.put("dataType", rs.getString("data_type"));
        row.put("defaultValue", rs.getString("default_value"));
        row.put("required", rs.getInt("required") != 0);
        row.put("businessLabel", rs.getString("business_label"));
        row.put("businessDescription", rs.getString("business_description"));
        return row;
    }

    private Map<String, Object> tableRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rawName", rs.getString("raw_name"));
        row.put("schemaResolved", rs.getString("schema_resolved"));
        row.put("tableResolved", rs.getString("table_resolved"));
        row.put("alias", rs.getString("alias"));
        row.put("role", rs.getString("role"));
        row.put("resolutionStatus", rs.getString("resolution_status"));
        return row;
    }

    private Map<String, Object> columnRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schemaResolved", rs.getString("schema_resolved"));
        row.put("tableResolved", rs.getString("table_resolved"));
        row.put("columnName", rs.getString("column_name"));
        row.put("context", rs.getString("context"));
        return row;
    }

    private Map<String, Object> joinRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("joinType", rs.getString("join_type"));
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("schema", rs.getString("left_schema"));
        left.put("table", rs.getString("left_table"));
        left.put("column", rs.getString("left_column"));
        row.put("left", left);
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("schema", rs.getString("right_schema"));
        right.put("table", rs.getString("right_table"));
        right.put("column", rs.getString("right_column"));
        row.put("right", right);
        row.put("onText", rs.getString("on_text"));
        row.put("equality", rs.getInt("equality") != 0);
        return row;
    }

    private Map<String, Object> outputRow(ResultSet rs, String uid) throws SQLException {
        long id = rs.getLong("id");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("alias", rs.getString("alias"));
        row.put("sourceExpression", rs.getString("source_expression"));
        row.put("businessLabel", rs.getString("business_label"));
        row.put("businessDescription", rs.getString("business_description"));
        row.put("derivedFromColumns", queryList(
                "SELECT * FROM query_output_column WHERE query_output_id = ?",
                ps -> ps.setLong(1, id),
                cs -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("schema", cs.getString("schema_resolved"));
                    c.put("table", cs.getString("table_resolved"));
                    c.put("column", cs.getString("column_name"));
                    return c;
                }));
        return row;
    }

    private Map<String, Object> fieldUsageRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("outputId", rs.getObject("query_output_id"));
        row.put("businessObject", rs.getString("business_object"));
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("kind", rs.getString("transformation_kind"));
        tx.put("description", rs.getString("transformation_description"));
        row.put("transformation", tx);
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("kind", rs.getString("location_kind"));
        loc.put("detailsJson", rs.getString("location_details_json"));
        row.put("location", loc);
        row.put("headersJson", rs.getString("headers_json"));
        row.put("confidence", rs.getString("confidence"));
        return row;
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
        int paramsStored;
        int tablesExtracted;
        int columnsExtracted;
        int joinPairsExtracted;
        int outputsStored;
        int fieldUsagesStored;
    }
}
