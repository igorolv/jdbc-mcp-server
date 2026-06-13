package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Scope configuration for the persistent structure snapshot.
 *
 * <p>{@code schemas} is the build-time input: which schemas {@code rebuildStructureSnapshot}
 * front-loads into the snapshot. Modelled on {@link UsageProperties#nativeSchemas} — a CSV string
 * parsed into a trimmed, non-blank list. When empty, callers fall back to {@code defaultSchema()}.
 *
 * <p>This is distinct from {@code catalog_meta.structure_schemas}, which records the schemas that
 * actually made it into the snapshot and drives scope checks for {@code searchObjects} /
 * {@code findTablesByName}.
 */
@ConfigurationProperties(prefix = "structure-snapshot")
public record StructureSnapshotProperties(List<String> schemas) {

    @ConstructorBinding
    public StructureSnapshotProperties {
        if (schemas == null) schemas = List.of();
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
