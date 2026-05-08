package ru.it_spectrum.ai.jdbc.mcp.usage;

import java.util.List;
import java.util.Map;

/**
 * Input payload for {@code ingestQuery}.
 *
 * <p>Records and nested records mirror the JSON shape that the MCP tool exposes. All collections
 * are nullable — missing means "the caller did not provide this layer", which is treated as
 * empty.
 *
 * <p>Identity is derived from {@code (dataSource, source.path, source.unit)} into a single
 * textual {@link UsageUid uid}; on re-ingest, child rows for the existing uid are deleted and
 * rewritten in one transaction.
 *
 * <p>The catalog stores two layers per query:
 * <ul>
 *     <li><b>Facts</b> — tables, columns, joins extracted by JSqlParser from {@code sql}.</li>
 *     <li><b>Semantics</b> — parameters, outputs, field usages provided by the caller. The caller
 *         knows the source artifact (BI Publisher report, DAO method, dashboard panel, etc.) and
 *         enriches the raw SQL with business labels, output mappings, and where each output is
 *         displayed. The server does not interpret the source format; it only persists what is
 *         given.</li>
 * </ul>
 */
public final class IngestPayload {

    private IngestPayload() {
    }

    public record Source(String kind, String path, String unit) {
    }

    public record Param(
            String name,
            String dataType,
            String defaultValue,
            Boolean required,
            String businessLabel,
            String businessDescription
    ) {
    }

    public record OutputColumn(String schema, String table, String column) {
    }

    public record Output(
            String alias,
            String sourceExpression,
            String businessLabel,
            String businessDescription,
            List<OutputColumn> derivedFromColumns
    ) {
    }

    public record Transformation(String kind, String description) {
    }

    public record Location(String kind, Map<String, Object> details) {
    }

    public record FieldUsage(
            String output,
            String businessObject,
            Transformation transformation,
            Location location,
            List<String> headers,
            String confidence
    ) {
    }

    public record Request(
            String dataSource,
            Source source,
            String businessLabel,
            String businessDomain,
            List<String> businessTags,
            String sql,
            List<Param> parameters,
            List<Output> outputs,
            List<FieldUsage> fieldUsages,
            Map<String, Object> sourceMeta
    ) {
    }
}
