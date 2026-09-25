package ru.it_spectrum.ai.jdbc.mcp.metadata;

/**
 * A named database object the request depends on does not exist. Tools report it with the error kind
 * {@code not_found} (plus {@code missing} and {@code name}), so an agent knows to fix the name rather
 * than retry.
 *
 * <p>Extends {@link IllegalArgumentException} so every tool that maps argument errors reports it
 * without a catch clause of its own.
 */
public class ObjectNotFoundException extends IllegalArgumentException {

    private final String objectKind;
    private final String objectName;

    public ObjectNotFoundException(String objectKind, String objectName) {
        super(objectKind + " '" + objectName + "' not found");
        this.objectKind = objectKind;
        this.objectName = objectName;
    }

    /** A table or view, qualified as {@code schema.table} when the schema is known. */
    public static ObjectNotFoundException table(String schema, String table) {
        return new ObjectNotFoundException("table",
                schema == null || schema.isBlank() ? table : schema + "." + table);
    }

    public String objectKind() {
        return objectKind;
    }

    public String objectName() {
        return objectName;
    }
}
