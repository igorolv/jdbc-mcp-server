package ru.it_spectrum.ai.jdbc.mcp.sql;

/**
 * Thrown by {@link ReadOnlyGuard} when a statement is rejected before reaching the database.
 */
public class SqlNotAllowedException extends RuntimeException {
    public SqlNotAllowedException(String message) {
        super(message);
    }
}
