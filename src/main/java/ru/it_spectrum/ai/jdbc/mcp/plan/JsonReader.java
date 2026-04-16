package ru.it_spectrum.ai.jdbc.mcp.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser — just enough to walk the PostgreSQL
 * {@code EXPLAIN (FORMAT JSON)} output. Returns {@link Map}/{@link List}/{@link String}/
 * {@link Number}/{@link Boolean}/{@code null}.
 *
 * <p>Deliberately thin (no schema validation, no streaming): PostgreSQL's plan JSON is
 * well-formed and small enough to parse in one pass. Pulling in Jackson for this would
 * add weight (and the project's other JSON handling is also hand-rolled for determinism).
 */
final class JsonReader {

    private final String src;
    private int pos;

    private JsonReader(String src) {
        this.src = src;
        this.pos = 0;
    }

    static Object parse(String json) {
        JsonReader r = new JsonReader(json);
        r.skipWs();
        Object value = r.readValue();
        r.skipWs();
        if (r.pos != r.src.length()) {
            throw new IllegalArgumentException("Unexpected trailing content at position " + r.pos);
        }
        return value;
    }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) throw err("unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> obj = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return obj;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            Object value = readValue();
            obj.put(key, value);
            skipWs();
            if (peek() == ',') {
                pos++;
                continue;
            }
            expect('}');
            return obj;
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> arr = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return arr;
        }
        while (true) {
            arr.add(readValue());
            skipWs();
            if (peek() == ',') {
                pos++;
                continue;
            }
            expect(']');
            return arr;
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw err("unterminated escape");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) throw err("truncated \\u escape");
                        int cp = Integer.parseInt(src.substring(pos, pos + 4), 16);
                        sb.append((char) cp);
                        pos += 4;
                    }
                    default -> throw err("invalid escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw err("unterminated string");
    }

    private Boolean readBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw err("expected boolean");
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw err("expected null");
    }

    private Number readNumber() {
        int start = pos;
        if (peek() == '-' || peek() == '+') pos++;
        while (pos < src.length() && isNumChar(src.charAt(pos))) pos++;
        String token = src.substring(start, pos);
        if (token.isEmpty()) throw err("expected number");
        try {
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            long l = Long.parseLong(token);
            return l;
        } catch (NumberFormatException e) {
            throw err("invalid number '" + token + "'");
        }
    }

    private boolean isNumChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private char peek() {
        if (pos >= src.length()) throw err("unexpected end of input");
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw err("expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException err(String msg) {
        int ctxStart = Math.max(0, pos - 20);
        int ctxEnd = Math.min(src.length(), pos + 20);
        return new IllegalArgumentException("JSON " + msg + " at pos " + pos
                + " near: '" + src.substring(ctxStart, ctxEnd) + "'");
    }
}
