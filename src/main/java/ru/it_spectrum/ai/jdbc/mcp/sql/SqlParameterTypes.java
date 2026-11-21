package ru.it_spectrum.ai.jdbc.mcp.sql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts flat MCP parameter values according to an optional {@code paramTypes} map
 * and binds them onto JDBC {@link PreparedStatement} instances.
 */
public final class SqlParameterTypes {

    private static final Pattern TIMESTAMP_OFFSET_SUFFIX =
            Pattern.compile("^(?:\\d{4}-\\d{2}-\\d{2})(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?)?([Zz]|[+-]\\d{2}:?\\d{2})$");

    private SqlParameterTypes() {
    }

    /**
     * Sentinel preserving SQL type information when a typed parameter value is {@code null},
     * including across {@link NamedParameterRewriter}.
     */
    public record TypedNull(int sqlType) {
    }

    public static Map<String, SqlParamType> toMap(List<SqlParamTypeEntry> paramTypes) {
        if (paramTypes == null || paramTypes.isEmpty()) {
            return Map.of();
        }
        Map<String, SqlParamType> map = new LinkedHashMap<>(paramTypes.size() * 2);
        for (int i = 0; i < paramTypes.size(); i++) {
            SqlParamTypeEntry entry = paramTypes.get(i);
            if (entry == null) {
                throw new IllegalArgumentException("paramTypes[" + i + "] must not be null.");
            }
            if (entry.param() == null || entry.param().isBlank()) {
                throw new IllegalArgumentException("paramTypes[" + i + "].param must not be blank.");
            }
            if (entry.type() == null) {
                throw new IllegalArgumentException(
                        "paramTypes value for '" + entry.param()
                                + "' must not be null. Supported types: date, timestamp, timestamptz, uuid, integer, decimal.");
            }
            String key = entry.param().trim();
            if (map.put(key, entry.type()) != null) {
                throw new IllegalArgumentException("Duplicate paramTypes entry for '" + key + "'.");
            }
        }
        return map;
    }

    public static Map<String, Object> convertNamed(Map<String, Object> namedParams,
                                                   List<SqlParamTypeEntry> paramTypes) {
        return convertNamed(namedParams, toMap(paramTypes));
    }

    public static Map<String, Object> convertNamed(Map<String, Object> namedParams,
                                                   Map<String, SqlParamType> paramTypes) {
        if (paramTypes == null || paramTypes.isEmpty()) {
            return namedParams;
        }
        for (Map.Entry<String, SqlParamType> entry : paramTypes.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("paramTypes key for namedParams must not be blank.");
            }
            if (key.startsWith(":")) {
                String bare = key.substring(1);
                throw new IllegalArgumentException(
                        "paramTypes key '" + key + "' must not start with ':'; use the bare name '" + bare + "'.");
            }
            if (!namedParams.containsKey(key)) {
                throw new IllegalArgumentException(
                        "paramTypes key '" + key + "' does not match any key in namedParams.");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "paramTypes value for '" + key
                                + "' must not be null. Supported types: date, timestamp, timestamptz, uuid, integer, decimal.");
            }
        }

        Map<String, Object> converted = new LinkedHashMap<>(namedParams.size() * 2);
        for (Map.Entry<String, Object> entry : namedParams.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            SqlParamType type = paramTypes.get(key);
            if (type == null) {
                converted.put(key, value);
                continue;
            }
            if (value instanceof List<?> list) {
                List<Object> items = new ArrayList<>(list.size());
                for (Object item : list) {
                    items.add(convertValue(type, item, key));
                }
                converted.put(key, items);
            } else {
                converted.put(key, convertValue(type, value, key));
            }
        }
        return converted;
    }

    public static List<Object> convertPositional(List<Object> params,
                                                 List<SqlParamTypeEntry> paramTypes) {
        return convertPositional(params, toMap(paramTypes));
    }

    public static List<Object> convertPositional(List<Object> params,
                                                 Map<String, SqlParamType> paramTypes) {
        if (paramTypes == null || paramTypes.isEmpty()) {
            return params;
        }
        List<Object> converted = new ArrayList<>(params);
        for (Map.Entry<String, SqlParamType> entry : paramTypes.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "paramTypes key for positional params must be a 1-based integer index ('1', '2', ...).");
            }
            int index;
            try {
                index = Integer.parseInt(key.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "paramTypes key '" + key + "' for positional params must be a 1-based integer index ('1', '2', ...).",
                        e);
            }
            if (index == 0) {
                throw new IllegalArgumentException(
                        "paramTypes keys for positional params are 1-based ('1', '2', ...), not 0-based; got '0'.");
            }
            if (index < 1 || index > params.size()) {
                throw new IllegalArgumentException(
                        "paramTypes positional index '" + key + "' is out of range 1.." + params.size() + ".");
            }
            SqlParamType type = entry.getValue();
            if (type == null) {
                throw new IllegalArgumentException(
                        "paramTypes value for '" + key
                                + "' must not be null. Supported types: date, timestamp, timestamptz, uuid, integer, decimal.");
            }
            converted.set(index - 1, convertValue(type, converted.get(index - 1), key));
        }
        return converted;
    }

    public static Object convertValue(SqlParamType type, Object value, String paramLabel) {
        if (value == null) {
            return new TypedNull(type.sqlType());
        }
        return switch (type) {
            case date -> toLocalDate(value, paramLabel);
            case timestamp -> toLocalDateTime(value, paramLabel);
            case timestamptz -> toOffsetDateTime(value, paramLabel);
            case uuid -> toUuid(value, paramLabel);
            case integer -> toLong(value, paramLabel);
            case decimal -> toBigDecimal(value, paramLabel);
        };
    }

    public static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        switch (value) {
            case null -> ps.setNull(index, Types.VARCHAR);
            case TypedNull typedNull -> ps.setNull(index, typedNull.sqlType());
            case LocalDate ld -> {
                try {
                    // Explicit Types.DATE preserves PostgreSQL Oid.DATE and Oracle DATE (ojdbc maps bare LocalDate to TIMESTAMP).
                    ps.setObject(index, ld, Types.DATE);
                } catch (SQLFeatureNotSupportedException e) {
                    ps.setDate(index, Date.valueOf(ld));
                }
            }
            case LocalDateTime ldt -> {
                try {
                    ps.setObject(index, ldt, Types.TIMESTAMP);
                } catch (SQLFeatureNotSupportedException e) {
                    ps.setTimestamp(index, Timestamp.valueOf(ldt));
                }
            }
            case Date d -> ps.setDate(index, d);
            case Timestamp ts -> ps.setTimestamp(index, ts);
            case OffsetDateTime odt -> {
                try {
                    ps.setObject(index, odt);
                } catch (SQLFeatureNotSupportedException e) {
                    ps.setTimestamp(index, Timestamp.from(odt.toInstant()));
                }
            }
            case BigDecimal bd -> ps.setBigDecimal(index, bd);
            case Long l -> ps.setLong(index, l);
            case Boolean b -> ps.setBoolean(index, b);
            case String s -> ps.setString(index, s);
            case UUID u -> ps.setObject(index, u);
            default -> ps.setObject(index, value);
        }
    }

    private static LocalDate toLocalDate(Object value, String paramLabel) {
        if (value instanceof LocalDate ld) {
            return ld;
        }
        if (value instanceof Date d) {
            return d.toLocalDate();
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            try {
                return LocalDate.parse(trimmed);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Invalid date value '" + trimmed + "' for parameter '" + paramLabel
                                + "'. Expected ISO-8601 date YYYY-MM-DD.",
                        e);
            }
        }
        throw new IllegalArgumentException(
                "Invalid date value of type " + value.getClass().getSimpleName()
                        + " for parameter '" + paramLabel + "'. Expected ISO-8601 string YYYY-MM-DD.");
    }

    private static LocalDateTime toLocalDateTime(Object value, String paramLabel) {
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDate ld) {
            return ld.atStartOfDay();
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            Matcher offsetMatcher = TIMESTAMP_OFFSET_SUFFIX.matcher(trimmed);
            if (offsetMatcher.matches()) {
                throw new IllegalArgumentException(
                        "Invalid timestamp value '" + trimmed + "' for parameter '" + paramLabel
                                + "': timezone offset '" + offsetMatcher.group(1)
                                + "' is not allowed for 'timestamp'. Use paramType 'timestamptz' or pass local ISO "
                                + "YYYY-MM-DDTHH:mm:ss without an offset.");
            }
            String normalized = normalizeSpaceTimestamp(trimmed);
            try {
                return LocalDateTime.parse(normalized);
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDate.parse(trimmed).atStartOfDay();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            "Invalid timestamp value '" + trimmed + "' for parameter '" + paramLabel
                                    + "'. Expected ISO-8601 YYYY-MM-DDTHH:mm:ss (or YYYY-MM-DD HH:mm:ss or YYYY-MM-DD).",
                            e);
                }
            }
        }
        throw new IllegalArgumentException(
                "Invalid timestamp value of type " + value.getClass().getSimpleName()
                        + " for parameter '" + paramLabel + "'. Expected ISO-8601 string YYYY-MM-DDTHH:mm:ss.");
    }

    private static String normalizeSpaceTimestamp(String s) {
        if (s.length() >= 13 && s.charAt(10) == ' ') {
            return s.substring(0, 10) + 'T' + s.substring(11);
        }
        return s;
    }

    private static OffsetDateTime toOffsetDateTime(Object value, String paramLabel) {
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            try {
                return OffsetDateTime.parse(trimmed);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Invalid timestamptz value '" + trimmed + "' for parameter '" + paramLabel
                                + "'. Expected ISO-8601 offset timestamp like YYYY-MM-DDTHH:mm:ssZ or +03:00 "
                                + "(or use paramType 'timestamp' without an offset).",
                        e);
            }
        }
        throw new IllegalArgumentException(
                "Invalid timestamptz value of type " + value.getClass().getSimpleName()
                        + " for parameter '" + paramLabel + "'. Expected ISO-8601 offset timestamp.");
    }

    private static UUID toUuid(Object value, String paramLabel) {
        if (value instanceof UUID u) {
            return u;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            try {
                return UUID.fromString(trimmed);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid uuid value '" + trimmed + "' for parameter '" + paramLabel + "'.",
                        e);
            }
        }
        throw new IllegalArgumentException(
                "Invalid uuid value of type " + value.getClass().getSimpleName()
                        + " for parameter '" + paramLabel + "'. Expected UUID string.");
    }

    private static Long toLong(Object value, String paramLabel) {
        switch (value) {
            case Byte b -> {
                return b.longValue();
            }
            case Short s -> {
                return s.longValue();
            }
            case Integer i -> {
                return i.longValue();
            }
            case Long l -> {
                return l;
            }
            case BigInteger bi -> {
                try {
                    return bi.longValueExact();
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException(
                            "Integer value '" + bi + "' out of 64-bit range for parameter '" + paramLabel + "'.",
                            e);
                }
            }
            case BigDecimal bd -> {
                try {
                    return bd.longValueExact();
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException(
                            "Invalid integer value '" + bd + "' for parameter '" + paramLabel + "'.",
                            e);
                }
            }
            case String s -> {
                String trimmed = s.trim();
                try {
                    return Long.parseLong(trimmed);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid integer value '" + trimmed + "' for parameter '" + paramLabel + "'.",
                            e);
                }
            }
            default -> throw new IllegalArgumentException(
                    "Invalid integer value of type " + value.getClass().getSimpleName()
                            + " for parameter '" + paramLabel + "'.");
        }
    }

    private static BigDecimal toBigDecimal(Object value, String paramLabel) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float f) {
            if (!Float.isFinite(f)) {
                throw new IllegalArgumentException(
                        "Non-finite decimal value '" + f + "' for parameter '" + paramLabel + "'.");
            }
            return new BigDecimal(Float.toString(f));
        }
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException(
                        "Non-finite decimal value '" + d + "' for parameter '" + paramLabel + "'.");
            }
            return BigDecimal.valueOf(d);
        }
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid decimal value '" + n + "' for parameter '" + paramLabel + "'.",
                        e);
            }
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid decimal value '" + trimmed + "' for parameter '" + paramLabel + "'.",
                        e);
            }
        }
        throw new IllegalArgumentException(
                "Invalid decimal value of type " + value.getClass().getSimpleName()
                        + " for parameter '" + paramLabel + "'.");
    }
}
