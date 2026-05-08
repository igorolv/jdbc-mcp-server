package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Input payload for {@code ingestQuery}.
 *
 * <p>Records and nested records mirror the JSON shape that the MCP tool exposes. Fields marked with
 * {@code @JsonProperty(required = false)} are optional and may be omitted; everything else is
 * required for a meaningful record. The same payload type is reused by the MCP tool entry point
 * and by future bulk loaders that deserialise JSON files directly with Jackson.
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

    public enum TransformationKind {
        IDENTITY, AGGREGATE, DERIVED, CONDITIONAL, FILTER, FORMAT, DECODE, OTHER;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static TransformationKind fromJson(String value) {
            if (value == null) return null;
            return TransformationKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Confidence {
        HIGH, MEDIUM, LOW;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Confidence fromJson(String value) {
            if (value == null) return null;
            return Confidence.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record Source(
            @JsonPropertyDescription("Free-text classifier of the artifact this query lives in (e.g. 'bi-publisher-report', 'dao', 'dashboard').")
            String kind,
            @JsonPropertyDescription("Path of the artifact (file, module, URL). Must not contain '#'.")
            String path,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Sub-unit inside the artifact: dataset name, DAO method, dashboard panel id. Optional. Must not contain '/' or '#'.")
            String unit
    ) {
    }

    public record Param(
            @JsonPropertyDescription("Bind name (without ':' / '?'). Used to match the caller-supplied semantics to parser-detected bindings; falls back to ordinal when absent on either side.")
            String name,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Declared SQL data type as a free-text label (e.g. 'NUMBER', 'VARCHAR2(50)', 'date').")
            String dataType,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Default value as a string, if any.")
            String defaultValue,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Whether the parameter is mandatory in the consuming artifact.")
            Boolean required,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Short business label of the parameter (UI prompt, report parameter caption).")
            String businessLabel,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Longer business description: meaning, allowed values, source of the value.")
            String businessDescription
    ) {
    }

    public record OutputColumn(
            @JsonProperty(required = false)
            @JsonPropertyDescription("Schema name of the underlying physical column. Optional when unknown or when the column is unqualified in the SQL.")
            String schema,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Table name of the underlying physical column. Optional when the output is computed from constants/expressions only.")
            String table,
            @JsonPropertyDescription("Physical column name the output is derived from.")
            String column
    ) {
    }

    public record Output(
            @JsonPropertyDescription("Output column alias as it appears in the SELECT list (case-sensitive).")
            String alias,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Original SQL expression that produced this output, copied verbatim from the SELECT clause.")
            String sourceExpression,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Short business label of the output (column header, field caption).")
            String businessLabel,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Longer business description of the output.")
            String businessDescription,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Physical columns that the output is derived from. Empty/absent when the output is constant or computed from non-column expressions only.")
            List<OutputColumn> derivedFromColumns
    ) {
    }

    public record Transformation(
            @JsonPropertyDescription("How the value is transformed before being shown. 'identity' = passed through unchanged.")
            TransformationKind kind,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Free-text description of the transformation (formula, formatting, mapping rule).")
            String description
    ) {
    }

    public record Location(
            @JsonPropertyDescription("Where the value is rendered: 'excel-cell', 'rtf-region', 'ui-label', 'dashboard-widget', etc. Free-text.")
            String kind,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Arbitrary key/value details about the location (e.g. {sheet:'Summary', cell:'B12'} or {widgetId:'lblName'}). Stored as JSON.")
            Map<String, Object> details
    ) {
    }

    public record FieldUsage(
            @JsonProperty(required = false)
            @JsonPropertyDescription("Output alias from the 'outputs' list this usage is for. May be null when the usage is not tied to a specific output column.")
            String output,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Business object / area / screen where the value is displayed (free-text grouping aid).")
            String businessObject,
            @JsonPropertyDescription("Required. How the value is transformed before display.")
            Transformation transformation,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Where in the consuming artifact the value is rendered.")
            Location location,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Surrounding header/label text near the rendered value (helps the agent reconstruct the visual context).")
            List<String> headers,
            @JsonProperty(required = false)
            @JsonPropertyDescription("How confident the caller is in this usage record.")
            Confidence confidence
    ) {
    }

    public record Request(
            @JsonPropertyDescription("Logical database identifier scoping this query (e.g. 'SHOP'). Must not contain '/' or '#'.")
            String dataSource,
            @JsonPropertyDescription("Where this query comes from (kind/path/unit).")
            Source source,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Short business label of the query.")
            String businessLabel,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Business domain/area for grouping (free-text; see listKnownDomains).")
            String businessDomain,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Discovery tags (free-text; see listKnownTags).")
            List<String> businessTags,
            @JsonPropertyDescription("SQL text. Named (':name') or positional ('?') bindings. Parsing failures are tolerated — the query is stored with parseStatus='failed' so business metadata is not lost.")
            String sql,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Parameters with optional business descriptions (matched to parser by name when possible, else by ordinal).")
            List<Param> parameters,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Output columns of the query with their business meaning and (optionally) the underlying physical columns they are derived from.")
            List<Output> outputs,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Where in the consuming artifact each output is displayed (Excel cell, RTF region, dashboard widget, …) including transformation kind.")
            List<FieldUsage> fieldUsages,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Arbitrary JSON blob preserved verbatim for audit (the original source artifact, etc.).")
            Map<String, Object> sourceMeta
    ) {
    }
}
