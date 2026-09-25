package ru.it_spectrum.ai.jdbc.mcp.config;

/**
 * Supported database engines. The concrete engine is auto-detected from the JDBC URL prefix;
 * {@link #GENERIC} — any other JDBC driver, answered through {@code DatabaseMetaData} — is never
 * inferred from a URL alone, see {@link #resolve}.
 */
public enum DatabaseKind {
    POSTGRESQL("PostgreSQL"),
    ORACLE("Oracle"),
    MSSQL("SQL Server"),
    FIREBIRD("Firebird"),
    SQLITE("SQLite"),
    GENERIC("Generic JDBC");

    private final String displayName;

    DatabaseKind(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable engine name, as reported by {@code listConnections}. */
    public String displayName() {
        return displayName;
    }

    /**
     * The engine of one connection.
     *
     * @param explicitDialect the {@code dialect} a connection names ({@code postgresql},
     *                        {@code oracle}, {@code mssql}, {@code firebird}, {@code sqlite} or
     *                        {@code generic}, any case), or {@code null} to detect it from the URL
     * @param externalDriver  whether the connection brings its own driver ({@code driverPath});
     *                        a URL no dialect recognizes is then served as {@link #GENERIC}
     * @throws IllegalArgumentException for an unknown dialect name, or an unrecognized URL without
     *                                  an external driver
     */
    public static DatabaseKind resolve(String url, String explicitDialect, boolean externalDriver) {
        if (explicitDialect != null && !explicitDialect.isBlank()) {
            String name = explicitDialect.trim().toUpperCase(java.util.Locale.ROOT);
            for (DatabaseKind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown dialect '" + explicitDialect
                    + "'. Use one of postgresql, oracle, mssql, firebird, sqlite, generic");
        }
        try {
            return fromUrl(url);
        } catch (IllegalArgumentException e) {
            if (externalDriver && url != null && !url.isBlank()) {
                return GENERIC;
            }
            throw new IllegalArgumentException(e.getMessage()
                    + ". For another database, set \"driverPath\" to its JDBC driver jar"
                    + " (served as generic JDBC).", e);
        }
    }

    /**
     * Detects the database kind by JDBC URL prefix.
     *
     * @throws IllegalArgumentException if the URL does not match any supported kind
     */
    public static DatabaseKind fromUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "JDBC_URL is not set. Provide e.g. jdbc:postgresql://host:5432/db " +
                            "or jdbc:oracle:thin:@//host:1521/service " +
                            "or jdbc:sqlserver://host:1433;databaseName=db " +
                            "or jdbc:firebirdsql://host:3050//path/to/db.fdb " +
                            "or jdbc:sqlite:/path/to/app.db");
        }
        String u = url.trim().toLowerCase();
        if (u.startsWith("jdbc:postgresql:")) {
            return POSTGRESQL;
        }
        if (u.startsWith("jdbc:oracle:")) {
            return ORACLE;
        }
        if (u.startsWith("jdbc:sqlserver:")) {
            return MSSQL;
        }
        if (u.startsWith("jdbc:firebirdsql:") || u.startsWith("jdbc:firebird:")) {
            return FIREBIRD;
        }
        if (u.startsWith("jdbc:sqlite:")) {
            return SQLITE;
        }
        throw new IllegalArgumentException(
                "Unsupported JDBC URL: '" + url + "'. Supported prefixes: " +
                        "jdbc:postgresql:, jdbc:oracle:, jdbc:sqlserver:, jdbc:firebirdsql:, jdbc:firebird:, jdbc:sqlite:");
    }
}
