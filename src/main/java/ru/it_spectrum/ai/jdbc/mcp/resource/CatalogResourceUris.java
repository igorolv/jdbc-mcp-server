package ru.it_spectrum.ai.jdbc.mcp.resource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Catalog-qualified URI construction and strict path-segment parsing for JDBC resources. */
final class CatalogResourceUris {

    private static final String SCHEME = "jdbc-mcp";
    private static final String AUTHORITY = "catalog";

    private final String catalog;
    private final String prefix;

    CatalogResourceUris(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            throw new IllegalArgumentException("catalog must not be blank");
        }
        this.catalog = catalog;
        this.prefix = SCHEME + "://" + AUTHORITY + "/" + encodeSegment(catalog);
    }

    String manifest() {
        return prefix + "/manifest";
    }

    String tableTemplate() {
        return prefix + "/schemas/{schema}/tables/{table}";
    }

    String table(String schema, String table) {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema must not be blank");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        return prefix + "/schemas/" + encodeSegment(schema) + "/tables/" + encodeSegment(table);
    }

    String columnTemplate() {
        return tableTemplate() + "/columns/{column}";
    }

    TableRef parseTable(String uri) {
        List<String> parts = parse(uri);
        if (parts.size() != 5 || !"schemas".equals(parts.get(1)) || !"tables".equals(parts.get(3))) {
            throw new IllegalArgumentException("URI is not a table resource for catalog '" + catalog + "'");
        }
        return new TableRef(parts.get(2), parts.get(4));
    }

    ColumnRef parseColumn(String uri) {
        List<String> parts = parse(uri);
        if (parts.size() != 7 || !"schemas".equals(parts.get(1)) || !"tables".equals(parts.get(3))
                || !"columns".equals(parts.get(5))) {
            throw new IllegalArgumentException("URI is not a column resource for catalog '" + catalog + "'");
        }
        return new ColumnRef(parts.get(2), parts.get(4), parts.get(6));
    }

    void requireManifest(String uri) {
        List<String> parts = parse(uri);
        if (parts.size() != 2 || !"manifest".equals(parts.get(1))) {
            throw new IllegalArgumentException("URI is not the manifest resource for catalog '" + catalog + "'");
        }
    }

    private List<String> parse(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed JDBC resource URI", e);
        }
        if (!SCHEME.equalsIgnoreCase(uri.getScheme()) || !AUTHORITY.equalsIgnoreCase(uri.getRawAuthority())
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("URI is outside the jdbc-mcp://catalog namespace");
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.length() < 2 || rawPath.charAt(0) != '/') {
            throw new IllegalArgumentException("JDBC resource URI has no path");
        }
        String[] rawParts = rawPath.substring(1).split("/", -1);
        List<String> parts = new ArrayList<>(rawParts.length);
        for (String rawPart : rawParts) {
            if (rawPart.isEmpty()) throw new IllegalArgumentException("JDBC resource URI has an empty path segment");
            parts.add(decodeSegment(rawPart));
        }
        if (parts.isEmpty() || !catalog.equals(parts.getFirst())) {
            throw new IllegalArgumentException("URI belongs to another JDBC catalog");
        }
        return parts;
    }

    static String encodeSegment(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0x0f, 16)));
            }
        }
        return out.toString();
    }

    static String decodeSegment(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) throw new IllegalArgumentException("Invalid percent escape in URI");
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid percent escape in URI");
                bytes.write((high << 4) | low);
                i += 3;
            } else {
                int codePoint = value.codePointAt(i);
                bytes.writeBytes(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(codePoint);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    record TableRef(String schema, String table) {
    }

    record ColumnRef(String schema, String table, String column) {
    }
}
