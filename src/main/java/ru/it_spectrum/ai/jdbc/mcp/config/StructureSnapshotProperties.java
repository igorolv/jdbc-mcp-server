package ru.it_spectrum.ai.jdbc.mcp.config;


import java.util.ArrayList;
import java.util.List;

/**
 * Scope configuration for the persistent structure snapshot.
 *
 * <p>{@code schemas} is the build-time input: which schemas {@code rebuildStructureSnapshot}
 * front-loads into the snapshot. Modelled on {@link UsageProperties#nativeSchemas} — a CSV string
 * parsed into a trimmed, non-blank list. When empty, callers fall back to {@code defaultSchema()}.
 * {@code oracleColumnQueryTimeoutSeconds} overrides the ordinary JDBC statement timeout only for
 * Oracle's expensive bulk column/default query during a full rebuild; zero disables that timeout.
 *
 * <p>This is distinct from {@code catalog_meta.structure_schemas}, which records the schemas that
 * actually made it into the snapshot and drives scope checks for {@code searchObjects} /
 * {@code findTablesByName}.
 */
public record StructureSnapshotProperties(
        List<String> schemas,
        int oracleColumnQueryTimeoutSeconds
) {

    /** Values used for every field a {@code connections.json} entry does not set. */
    public static final StructureSnapshotProperties DEFAULTS =
            new StructureSnapshotProperties(List.of(), 300);

    public StructureSnapshotProperties {
        if (schemas == null) schemas = List.of();
        if (oracleColumnQueryTimeoutSeconds < 0) oracleColumnQueryTimeoutSeconds = 0;
    }

    /** CSV → trimmed non-blank list, like {@link UsageProperties#resolvedNativeSchemas()}. */
    public List<String> resolvedSchemas() {
        List<String> out = new ArrayList<>();
        for (String raw : schemas) {
            if (raw == null || raw.isBlank()) continue;
            for (String part : raw.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        return List.copyOf(out);
    }
}
