package ru.it_spectrum.ai.jdbc.mcp.usage.format;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.Map;

/**
 * Canonical query-usage record consumed by the usage catalog.
 *
 * <p>This is the source-agnostic public format of the JDBC MCP server. Usage catalog source
 * directories and zip archives contain JSON documents in this shape.
 *
 * <p>Identity is derived from {@code (dataSource, source.path, source.unit)} into a single textual
 * uid. Re-ingesting the same UID replaces child rows in one transaction.
 */
public record QueryUsage(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Canonical query-usage record schema version. Omit or set to 1 for the current format.")
        Integer schemaVersion,
        @JsonPropertyDescription("Logical database identifier scoping this query (e.g. 'SHOP'). Must not contain '/' or '#'.")
        String dataSource,
        @JsonPropertyDescription("Where this query comes from (kind/path/unit).")
        QueryUsageSource source,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Short business label of the query.")
        String businessLabel,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Business domain/area for grouping (free-text; see listKnownDomains).")
        String businessDomain,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Discovery tags (free-text; see listKnownTags).")
        List<String> businessTags,
        @JsonPropertyDescription("SQL text. Named (':name') or positional ('?') bindings. Parsing failures are tolerated; the query is stored with parseStatus='failed' so business metadata is not lost.")
        String sql,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Parameters with optional business descriptions (matched to parser by name when possible, else by ordinal).")
        List<QueryUsageParameter> parameters,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Output columns of the query with their business meaning and (optionally) the underlying physical columns they are derived from.")
        List<QueryUsageOutput> outputs,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Where in the consuming artifact each output is displayed, including transformation kind.")
        List<QueryUsageFieldUsage> fieldUsages,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Arbitrary JSON blob preserved verbatim for audit/provenance.")
        Map<String, Object> sourceMeta
) {
    public QueryUsage {
        if (schemaVersion == null) {
            schemaVersion = 1;
        }
    }

    public QueryUsage(String dataSource,
                      QueryUsageSource source,
                      String businessLabel,
                      String businessDomain,
                      List<String> businessTags,
                      String sql,
                      List<QueryUsageParameter> parameters,
                      List<QueryUsageOutput> outputs,
                      List<QueryUsageFieldUsage> fieldUsages,
                      Map<String, Object> sourceMeta) {
        this(1, dataSource, source, businessLabel, businessDomain, businessTags, sql,
                parameters, outputs, fieldUsages, sourceMeta);
    }
}
