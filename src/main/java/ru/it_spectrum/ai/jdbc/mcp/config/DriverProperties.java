package ru.it_spectrum.ai.jdbc.mcp.config;

/**
 * Which dialect serves a connection and where its JDBC driver comes from.
 *
 * @param dialect     explicit engine ({@code postgresql}, {@code oracle}, {@code mssql},
 *                    {@code firebird}, {@code sqlite}, {@code generic}); {@code null} detects it
 *                    from the URL
 * @param driverPath  a driver jar, or a directory whose {@code *.jar} files are all loaded, in a class
 *                    loader of its own; {@code null} uses the drivers bundled in the server jar
 * @param driverClass the {@link java.sql.Driver} class in {@code driverPath}; {@code null} picks the
 *                    driver that accepts the URL among those the jars register
 */
public record DriverProperties(String dialect, String driverPath, String driverClass) {

    /** Bundled drivers, dialect detected from the URL. */
    public static final DriverProperties DEFAULTS = new DriverProperties(null, null, null);

    public boolean externalDriver() {
        return driverPath != null && !driverPath.isBlank();
    }
}
