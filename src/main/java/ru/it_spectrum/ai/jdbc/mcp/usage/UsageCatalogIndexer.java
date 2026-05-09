package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.IndexerStatusResponse;
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

@Service
public class UsageCatalogIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UsageCatalogIndexer.class);

    private final UsageProperties properties;
    private final JdbcMcpProperties jdbcMcpProperties;
    private final UsageCatalogService service;
    private final ObjectMapper mapper;
    private final List<UsageCatalogSource> catalogSources;
    private final AtomicBoolean indexing = new AtomicBoolean(false);
    private volatile IndexerStatusResponse status;

    public UsageCatalogIndexer(UsageProperties properties, JdbcMcpProperties jdbcMcpProperties,
                               UsageCatalogService service, ObjectMapper mapper) {
        this(properties, jdbcMcpProperties, service, mapper, List.of());
    }

    @Autowired
    public UsageCatalogIndexer(UsageProperties properties, JdbcMcpProperties jdbcMcpProperties,
                               UsageCatalogService service, ObjectMapper mapper,
                               List<UsageCatalogSource> catalogSources) {
        this.properties = properties;
        this.jdbcMcpProperties = jdbcMcpProperties;
        this.service = service;
        this.mapper = mapper;
        this.catalogSources = catalogSources == null ? List.of() : List.copyOf(catalogSources);
        this.status = IndexerStatusResponse.initial(
                properties.catalogEnabled(),
                configuredSources()
        );
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

    public IndexerStatusResponse status() {
        return status;
    }

    public IndexerStatusResponse refresh() {
        return properties.indexBackground() ? refreshAsync() : refreshBlocking();
    }

    public IndexerStatusResponse refreshAsync() {
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

    public IndexerStatusResponse refreshBlocking() {
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
        var sources = configuredSources();
        var startedAt = Instant.ofEpochMilli(started).toString();
        var diskCacheEnabled = properties.indexDiskCacheEnabled();
        var diskCachePath = jdbcMcpProperties.usageIndexCacheDir().toString();
        try {
            var rebuildResult = service.rebuild(loaded.records());
            status = IndexerStatusResponse.ready(
                    properties.catalogEnabled(), "ready", sources,
                    startedAt, loaded.filesScanned(), loaded.records().size(),
                    loaded.errors().size(), loaded.duplicateUids().size(),
                    loaded.errors(), loaded.duplicateUids(),
                    Instant.now().toString(), Instant.now().toString(),
                    System.currentTimeMillis() - started,
                    diskCacheEnabled, diskCachePath,
                    rebuildResult.parseFailed(), rebuildResult.paramsStored(),
                    rebuildResult.tablesExtracted(), rebuildResult.columnsExtracted(),
                    rebuildResult.joinPairsExtracted(), rebuildResult.outputsStored(),
                    rebuildResult.fieldUsagesStored(), rebuildResult.indexBuildMs()
            );
            log.info("Usage catalog index built: records={}, invalid={}, duplicates={}, ms={}",
                    loaded.records().size(), loaded.errors().size(), loaded.duplicateUids().size(),
                    System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            List<String> errors = appendError(loaded.errors(), "index rebuild failed: " + e.getMessage());
            status = IndexerStatusResponse.ready(
                    properties.catalogEnabled(), "failed", sources,
                    startedAt, loaded.filesScanned(), loaded.records().size(),
                    loaded.errors().size() + 1, loaded.duplicateUids().size(),
                    errors, loaded.duplicateUids(),
                    null, Instant.now().toString(),
                    System.currentTimeMillis() - started,
                    diskCacheEnabled, diskCachePath,
                    null, null, null, null, null, null, null, null
            );
            log.warn("Usage catalog index rebuild failed: {}", e.getMessage(), e);
        }
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
            addRecord(origin, usage, records, duplicateUids, errors);
        } catch (RuntimeException | IOException e) {
            errors.add(origin + ": " + e.getMessage());
        }
    }

    private void markIndexing() {
        status = IndexerStatusResponse.indexing(
                properties.catalogEnabled(),
                configuredSources(),
                properties.indexDiskCacheEnabled(),
                jdbcMcpProperties.usageIndexCacheDir().toString()
        );
    }

    private void addRecord(String origin, QueryUsage usage, Map<String, QueryUsage> records,
                           Set<String> duplicateUids, List<String> errors) {
        QueryUsageSource source = usage.source();
        String unit = source == null ? null : source.unit();
        String uid = UsageUid.build(usage.dataSource(), source == null ? null : source.path(), unit);
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
        Path defaultDir = jdbcMcpProperties.usageCatalogDir();
        if (Files.isDirectory(defaultDir)) {
            paths.add(defaultDir);
        }
        paths.addAll(properties.additionalCatalogPaths());
        return List.copyOf(paths);
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
