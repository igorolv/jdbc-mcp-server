package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds the runtime usage-catalog index from canonical QueryUsage JSON files.
 *
 * <p>Directories are scanned recursively for {@code *.json}; zip archives are scanned for JSON
 * entries. The files remain the source of truth. Rebuilds replace the in-memory relational index
 * in one transaction through {@link UsageCatalogService#rebuild(List)}.
 */
@Service
public class UsageCatalogIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UsageCatalogIndexer.class);

    private final UsageProperties properties;
    private final UsageCatalogService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean indexing = new AtomicBoolean(false);
    private volatile Map<String, Object> status;

    public UsageCatalogIndexer(UsageProperties properties, UsageCatalogService service) {
        this.properties = properties;
        this.service = service;
        this.status = initialStatus();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.catalogEnabled() || !properties.indexOnStartup()) {
            return;
        }
        if (properties.indexBackground()) {
            refreshAsync();
        } else {
            refreshBlocking();
        }
    }

    public Map<String, Object> status() {
        return status;
    }

    public Map<String, Object> refresh() {
        return properties.indexBackground() ? refreshAsync() : refreshBlocking();
    }

    public Map<String, Object> refreshAsync() {
        if (!indexing.compareAndSet(false, true)) {
            return status;
        }
        markIndexing();
        Thread worker = new Thread(() -> {
            try {
                rebuild();
            } finally {
                indexing.set(false);
            }
        }, "usage-catalog-indexer");
        worker.setDaemon(true);
        worker.start();
        return status;
    }

    public Map<String, Object> refreshBlocking() {
        if (!indexing.compareAndSet(false, true)) {
            return status;
        }
        markIndexing();
        try {
            rebuild();
            return status;
        } finally {
            indexing.set(false);
        }
    }

    private void rebuild() {
        long started = System.currentTimeMillis();
        LoadResult loaded = loadRecords();
        try {
            Map<String, Object> rebuild = service.rebuild(loaded.records());
            Map<String, Object> out = baseStatus("ready", started);
            out.put("filesScanned", loaded.filesScanned());
            out.put("recordsLoaded", loaded.records().size());
            out.put("invalidFiles", loaded.errors().size());
            out.put("duplicateUids", loaded.duplicateUids().size());
            out.put("errors", loaded.errors());
            out.put("duplicates", loaded.duplicateUids());
            out.putAll(rebuild);
            out.put("lastSuccessfulBuildAt", Instant.now().toString());
            out.put("finishedAt", Instant.now().toString());
            out.put("totalBuildMs", System.currentTimeMillis() - started);
            status = Map.copyOf(out);
            log.info("Usage catalog index built: records={}, invalid={}, duplicates={}, ms={}",
                    loaded.records().size(), loaded.errors().size(), loaded.duplicateUids().size(),
                    System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            Map<String, Object> out = baseStatus("failed", started);
            out.put("filesScanned", loaded.filesScanned());
            out.put("recordsLoaded", loaded.records().size());
            out.put("invalidFiles", loaded.errors().size());
            out.put("duplicateUids", loaded.duplicateUids().size());
            out.put("errors", appendError(loaded.errors(), "index rebuild failed: " + e.getMessage()));
            out.put("duplicates", loaded.duplicateUids());
            out.put("finishedAt", Instant.now().toString());
            out.put("totalBuildMs", System.currentTimeMillis() - started);
            status = Map.copyOf(out);
            log.warn("Usage catalog index rebuild failed: {}", e.getMessage(), e);
        }
    }

    private LoadResult loadRecords() {
        Map<String, QueryUsage> records = new LinkedHashMap<>();
        Set<String> duplicateUids = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        int[] filesScanned = {0};
        for (Path source : properties.resolvedCatalogPaths()) {
            try {
                loadSource(source, records, duplicateUids, errors, filesScanned);
            } catch (RuntimeException | IOException e) {
                errors.add(source + ": " + e.getMessage());
            }
        }
        return new LoadResult(List.copyOf(records.values()), filesScanned[0],
                List.copyOf(errors), List.copyOf(duplicateUids));
    }

    private void loadSource(Path source, Map<String, QueryUsage> records, Set<String> duplicateUids,
                            List<String> errors, int[] filesScanned) throws IOException {
        if (!Files.exists(source)) {
            errors.add(source + ": source does not exist");
            return;
        }
        if (Files.isDirectory(source)) {
            try (Stream<Path> paths = Files.walk(source)) {
                List<Path> jsonFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(UsageCatalogIndexer::isJsonFile)
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
                    .filter(e -> e.getName().toLowerCase().endsWith(".json"))
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
            QueryUsage usage = mapper.readValue(bytes, QueryUsage.class);
            QueryUsageSource source = usage.source();
            String unit = source == null ? null : source.unit();
            String uid = UsageUid.build(usage.dataSource(), source == null ? null : source.path(), unit);
            if (records.containsKey(uid)) {
                duplicateUids.add(uid);
                errors.add(origin + ": duplicate uid " + uid);
                return;
            }
            records.put(uid, usage);
        } catch (RuntimeException | IOException e) {
            errors.add(origin + ": " + e.getMessage());
        }
    }

    private void markIndexing() {
        Map<String, Object> out = baseStatus("indexing", System.currentTimeMillis());
        out.put("filesScanned", 0);
        out.put("recordsLoaded", 0);
        out.put("invalidFiles", 0);
        out.put("duplicateUids", 0);
        out.put("errors", List.of());
        out.put("duplicates", List.of());
        status = Map.copyOf(out);
    }

    private Map<String, Object> baseStatus(String state, long startedMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog_enabled", properties.catalogEnabled());
        out.put("state", state);
        out.put("indexing", "indexing".equals(state));
        out.put("sources", properties.resolvedCatalogPaths().stream().map(Path::toString).toList());
        out.put("startedAt", Instant.ofEpochMilli(startedMs).toString());
        out.put("diskCacheEnabled", properties.indexDiskCacheEnabled());
        out.put("diskCachePath", properties.resolvedIndexCachePath().toString());
        return out;
    }

    private Map<String, Object> initialStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog_enabled", properties.catalogEnabled());
        out.put("state", "not_started");
        out.put("indexing", false);
        out.put("sources", properties.resolvedCatalogPaths().stream().map(Path::toString).toList());
        return Map.copyOf(out);
    }

    private static List<String> appendError(List<String> errors, String error) {
        List<String> out = new ArrayList<>(errors);
        out.add(error);
        return List.copyOf(out);
    }

    private static boolean isJsonFile(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".json");
    }

    private static boolean isZipFile(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".zip");
    }

    private record LoadResult(List<QueryUsage> records, int filesScanned,
                              List<String> errors, List<String> duplicateUids) {
    }
}
