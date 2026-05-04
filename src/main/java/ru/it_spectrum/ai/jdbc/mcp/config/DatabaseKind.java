package ru.it_spectrum.ai.jdbc.mcp.config;

/**
 * Supported database engines. The concrete engine is auto-detected from the JDBC URL prefix.
 */
public enum DatabaseKind {
    POSTGRESQL,
    ORACLE,
    MSSQL;

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
                            "or jdbc:sqlserver://host:1433;databaseName=db");
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
        throw new IllegalArgumentException(
                "Unsupported JDBC URL: '" + url + "'. Supported prefixes: " +
                        "jdbc:postgresql:, jdbc:oracle:, jdbc:sqlserver:");
    }
}
