package ru.it_spectrum.ai.jdbc.mcp.connection;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Resolves {@code ${ENV_VAR}} placeholders inside {@code connections.json} string values.
 *
 * <p>Keeping secrets out of the file itself is the point: a password is written as
 * {@code "${ASVA_SSJ_DB_PASSWORD}"} and read from the environment at startup. A missing variable is
 * a hard error naming both the variable and the field, never a silently empty password.
 */
public final class EnvironmentPlaceholders {

    private EnvironmentPlaceholders() {
    }

    /**
     * @param value  raw string from the file, may be {@code null}
     * @param field  human-readable location used in the error message, e.g. {@code connections.ssj.password}
     * @param lookup environment lookup, normally {@code System::getenv}
     * @throws IllegalStateException when a referenced variable is not set or the placeholder is unterminated
     */
    public static String resolve(String value, String field, UnaryOperator<String> lookup) {
        if (value == null || value.indexOf("${") < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) {
                out.append(value, i, value.length());
                break;
            }
            out.append(value, i, start);
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                throw new IllegalStateException("Unterminated ${...} placeholder in " + field
                        + " of the connections file");
            }
            String name = value.substring(start + 2, end).trim();
            if (name.isEmpty()) {
                throw new IllegalStateException("Empty ${} placeholder in " + field
                        + " of the connections file");
            }
            String resolved = lookup.apply(name);
            if (resolved == null) {
                throw new IllegalStateException("Environment variable '" + name + "' referenced by "
                        + field + " in the connections file is not set");
            }
            out.append(resolved);
            i = end + 1;
        }
        return out.toString();
    }

    /** Resolves every element of a list; {@code null} in, {@code null} out. */
    public static List<String> resolveAll(List<String> values, String field, UnaryOperator<String> lookup) {
        if (values == null) {
            return null;
        }
        List<String> out = new java.util.ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            out.add(resolve(values.get(i), field + "[" + i + "]", lookup));
        }
        return List.copyOf(out);
    }
}
