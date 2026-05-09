package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the local usage catalog.
 *
 * <p>The source of truth is one or more local directories / zip archives containing JSON files in
 * the canonical {@code QueryUsage} format. At runtime these records are indexed into an in-memory
 * relational index queried by the {@code findQueriesBy*} family.
 *
 * <p>Writes are local-only and affect only the runtime index. The inspected JDBC database remains
 * strictly read-only.
 *
 * @param catalogEnabled when {@code false}, ingest tools return a {@code disabled} error and
 *                       lookup tools return empty results with {@code catalog_enabled=false}
 * @param catalogPaths   directories, JSON files, or zip archives to scan for canonical records.
 */
@ConfigurationProperties(prefix = "usage")
public record UsageProperties(
        boolean catalogEnabled,
        List<String> catalogPaths,
        boolean indexOnStartup,
        boolean indexBackground,
        boolean indexDiskCacheEnabled,
        String indexCachePath,
        String dataSourceId,
        boolean nativeCatalogEnabled,
        List<String> nativeSchemas,
        boolean nativeIncludeViews,
        boolean nativeIncludeRoutines,
        boolean nativeIncludeTriggers,
        int nativeMaxObjects
) {

    @ConstructorBinding
    public UsageProperties {
        if (catalogPaths == null) {
            catalogPaths = List.of();
        }
        if (nativeSchemas == null) {
            nativeSchemas = List.of();
        }
        if (nativeMaxObjects <= 0) {
            nativeMaxObjects = 1_000;
        }
    }

    public UsageProperties(boolean catalogEnabled,
                           List<String> catalogPaths,
                           boolean indexOnStartup,
                           boolean indexBackground,
                           boolean indexDiskCacheEnabled,
                           String indexCachePath) {
        this(catalogEnabled, catalogPaths, indexOnStartup, indexBackground,
                indexDiskCacheEnabled, indexCachePath, "database",
                false, List.of(), true, true, true, 1_000);
    }

    public List<Path> resolvedCatalogPaths() {
        List<Path> out = new ArrayList<>();
        for (String raw : catalogPaths) {
            if (raw == null || raw.isBlank()) continue;
            for (String part : raw.split(",")) {
                if (!part.isBlank()) out.add(Paths.get(part.trim()));
            }
        }
        return List.copyOf(out);
    }

    public Path resolvedIndexCachePath() {
        if (indexCachePath != null && !indexCachePath.isBlank()) {
            return Paths.get(indexCachePath);
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = ".";
        }
        return Paths.get(home, ".jdbc-mcp", "usage-index-cache");
    }

    public String effectiveDataSourceId() {
        String raw = dataSourceId == null || dataSourceId.isBlank() ? "database" : dataSourceId.trim();
        return raw.replace('/', '_').replace('#', '_');
    }

    public List<String> resolvedNativeSchemas() {
        List<String> out = new ArrayList<>();
        for (String raw : nativeSchemas) {
            if (raw == null || raw.isBlank()) continue;
            for (String part : raw.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        return List.copyOf(out);
    }
}
