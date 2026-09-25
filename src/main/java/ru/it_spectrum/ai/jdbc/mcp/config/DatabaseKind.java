package ru.it_spectrum.ai.jdbc.mcp.config;

/**
 * Supported database engines. The concrete engine is auto-detected from the JDBC URL prefix.
 */
public enum DatabaseKind {
    POSTGRESQL("PostgreSQL"),
    ORACLE("Oracle"),
    MSSQL("SQL Server"),
    FIREBIRD("Firebird");

    private final String displayName;

    DatabaseKind(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable engine name, as reported by {@code listConnections}. */
    public String displayName() {
        return displayName;
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
                            "or jdbc:firebirdsql://host:3050//path/to/db.fdb");
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
        throw new IllegalArgumentException(
                "Unsupported JDBC URL: '" + url + "'. Supported prefixes: " +
                        "jdbc:postgresql:, jdbc:oracle:, jdbc:sqlserver:, jdbc:firebirdsql:, jdbc:firebird:");
    }
}
