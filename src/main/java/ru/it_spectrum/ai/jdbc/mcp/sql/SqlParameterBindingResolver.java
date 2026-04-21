package ru.it_spectrum.ai.jdbc.mcp.sql;

import java.util.List;
import java.util.Map;

/**
 * Validates that the SQL placeholder style matches the binding container used by the MCP caller.
 */
public final class SqlParameterBindingResolver {

    private SqlParameterBindingResolver() {
    }

    public static ParameterMode detectMode(String sql) {
        return analyze(sql).mode();
    }

    public static Binding resolve(String sql, List<Object> params, Map<String, Object> namedParams) {
        boolean hasPositional = params != null && !params.isEmpty();
        boolean hasNamed = namedParams != null && !namedParams.isEmpty();
        if (hasPositional && hasNamed) {
            throw new IllegalArgumentException("Use either 'params' or 'namedParams', not both.");
        }

        Analysis analysis = analyze(sql);
        return switch (analysis.mode()) {
            case NONE -> {
                if (hasPositional || hasNamed) {
                    throw new IllegalArgumentException(
                            "SQL contains no placeholders, so omit both 'params' and 'namedParams'.");
                }
                yield new Binding(analysis.mode(), null, null);
            }
            case POSITIONAL -> {
                if (hasNamed) {
                    throw new IllegalArgumentException(
                            "SQL contains '?' placeholders, so pass values in 'params', not 'namedParams'.");
                }
                if (!hasPositional) {
                    throw new IllegalArgumentException(
                            "SQL contains '?' placeholders, so pass values in 'params' as an array in placeholder order.");
                }
                yield new Binding(analysis.mode(), params, null);
            }
            case NAMED -> {
                if (hasPositional) {
                    throw new IllegalArgumentException(
                            "SQL contains named placeholders like '" + analysis.exampleNamedPlaceholder()
                                    + "', so pass values in 'namedParams', not 'params'.");
                }
                if (!hasNamed) {
                    throw new IllegalArgumentException(
                            "SQL contains named placeholders like '" + analysis.exampleNamedPlaceholder()
                                    + "', so pass values in 'namedParams' as an object.");
                }
                yield new Binding(analysis.mode(), null, namedParams);
            }
            case MIXED -> throw new IllegalArgumentException(
                    "SQL mixes '?' and ':name' placeholders; use only one placeholder style per statement.");
        };
    }

    private static Analysis analyze(String sql) {
        String text = sql == null ? "" : sql;
        boolean hasPositional = false;
        boolean hasNamed = false;
        String exampleNamedPlaceholder = ":userId";

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';

            if (c == '\'' ) {
                i = skipSingleQuoted(text, i);
                continue;
            }
            if (c == '"') {
                i = skipDoubleQuoted(text, i);
                continue;
            }
            if (c == '-' && next == '-') {
                i = skipLineComment(text, i + 2);
                continue;
            }
            if (c == '/' && next == '*') {
                i = skipBlockComment(text, i + 2);
                continue;
            }
            if (c == '?') {
                hasPositional = true;
                continue;
            }
            if (c == ':') {
                if (next == ':') {
                    i++;
                    continue;
                }
                if (isNamedParameterStart(next)) {
                    int start = i + 1;
                    int end = start + 1;
                    while (end < text.length() && isNamedParameterPart(text.charAt(end))) {
                        end++;
                    }
                    hasNamed = true;
                    if (":userId".equals(exampleNamedPlaceholder)) {
                        exampleNamedPlaceholder = ":" + text.substring(start, end);
                    }
                    i = end - 1;
                }
            }
        }

        ParameterMode mode;
        if (hasPositional && hasNamed) {
            mode = ParameterMode.MIXED;
        } else if (hasPositional) {
            mode = ParameterMode.POSITIONAL;
        } else if (hasNamed) {
            mode = ParameterMode.NAMED;
        } else {
            mode = ParameterMode.NONE;
        }

        return new Analysis(mode, exampleNamedPlaceholder);
    }

    private static int skipSingleQuoted(String text, int start) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return text.length() - 1;
    }

    private static int skipDoubleQuoted(String text, int start) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return text.length() - 1;
    }

    private static int skipLineComment(String text, int start) {
        int i = start;
        while (i < text.length() && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String text, int start) {
        int i = start;
        while (i + 1 < text.length()) {
            if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
                return i + 1;
            }
            i++;
        }
        return text.length() - 1;
    }

    private static boolean isNamedParameterStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamedParameterPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public enum ParameterMode {
        NONE,
        POSITIONAL,
        NAMED,
        MIXED
    }

    public record Binding(ParameterMode mode, List<Object> params, Map<String, Object> namedParams) {
    }

    private record Analysis(ParameterMode mode, String exampleNamedPlaceholder) {
    }
}
