package ru.it_spectrum.ai.jdbc.mcp.config;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * A JDBC driver loaded from {@code driverPath} into a class loader of its own.
 *
 * <p>The loader's parent is the platform class loader, so the external jars see neither the server's
 * classes nor its bundled drivers — two versions of one driver cannot collide. The driver is used
 * directly ({@link #dataSource}) rather than through {@link java.sql.DriverManager}, which refuses
 * drivers its caller's class loader cannot see.
 */
public final class ExternalDriver implements AutoCloseable {

    private final URLClassLoader loader;
    private final Driver driver;

    private ExternalDriver(URLClassLoader loader, Driver driver) {
        this.loader = loader;
        this.driver = driver;
    }

    /**
     * @param driverPath  a jar, or a directory whose {@code *.jar} files are all loaded
     * @param driverClass the driver class; {@code null} takes the registered driver that accepts {@code url}
     */
    public static ExternalDriver load(String driverPath, String driverClass, String url) {
        URLClassLoader loader = new URLClassLoader("jdbc-driver:" + driverPath, jarUrls(Path.of(driverPath)),
                ClassLoader.getPlatformClassLoader());
        try {
            return new ExternalDriver(loader, findDriver(loader, driverPath, driverClass, url));
        } catch (RuntimeException e) {
            closeQuietly(loader);
            throw e;
        }
    }

    public Driver driver() {
        return driver;
    }

    /**
     * Connects through the driver; {@code properties} are passed as driver properties.
     *
     * @param tolerateReadOnlyRefusal see {@link DriverDataSource}
     */
    public DataSource dataSource(String url, Map<String, String> properties, boolean tolerateReadOnlyRefusal) {
        return new DriverDataSource(driver, url, properties, tolerateReadOnlyRefusal);
    }

    @Override
    public void close() {
        closeQuietly(loader);
    }

    private static URL[] jarUrls(Path path) {
        List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.list(path)) {
                files.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .forEach(jars::add);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot list driverPath " + path + ": " + e.getMessage(), e);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException("driverPath " + path + " contains no .jar files");
            }
        } else if (Files.isRegularFile(path)) {
            jars.add(path);
        } else {
            throw new IllegalStateException("driverPath " + path + " does not exist");
        }
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Invalid driver jar " + jars.get(i), e);
            }
        }
        return urls;
    }

    private static Driver findDriver(ClassLoader loader, String driverPath, String driverClass, String url) {
        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class<?> type = Class.forName(driverClass.trim(), true, loader);
                return (Driver) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
                throw new IllegalStateException("Cannot load driver class " + driverClass + " from "
                        + driverPath + ": " + e, e);
            }
        }
        List<String> seen = new ArrayList<>();
        for (Driver candidate : ServiceLoader.load(Driver.class, loader)) {
            if (candidate.getClass().getClassLoader() != loader) {
                continue; // a platform driver, not one of ours
            }
            seen.add(candidate.getClass().getName());
            try {
                if (candidate.acceptsURL(url)) {
                    return candidate;
                }
            } catch (SQLException ignored) {
                // a driver that cannot judge the URL does not claim it
            }
        }
        throw new IllegalStateException("No driver in " + driverPath + " accepts the URL"
                + (seen.isEmpty() ? " (the jars register no java.sql.Driver; set \"driverClass\")"
                : " (registered: " + String.join(", ", seen) + "; set \"driverClass\" if one of them should)"));
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // nothing useful to do at shutdown
        }
    }
}
