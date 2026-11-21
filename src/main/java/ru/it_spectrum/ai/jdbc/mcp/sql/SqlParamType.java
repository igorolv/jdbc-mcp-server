package ru.it_spectrum.ai.jdbc.mcp.sql;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Explicit JDBC parameter types accepted in the optional {@code paramTypes} list on query/benchmark
 * MCP tools for string values that must bind as non-text JDBC types (dates, timestamps, UUIDs,
 * 64-bit integers, or exact decimals).
 *
 * <p>Enum constant names are lowercase because Spring AI's schema generator derives the JSON Schema
 * {@code enum} array from {@link Enum#name()}.
 */
public enum SqlParamType {
    date("date", Types.DATE),
    timestamp("timestamp", Types.TIMESTAMP),
    timestamptz("timestamptz", Types.TIMESTAMP_WITH_TIMEZONE),
    uuid("uuid", Types.OTHER),
    integer("integer", Types.BIGINT),
    decimal("decimal", Types.DECIMAL);

    private static final List<String> WIRE_NAMES = Arrays.stream(values())
            .map(SqlParamType::wireName)
            .toList();

    private final String wireName;
    private final int sqlType;

    SqlParamType(String wireName, int sqlType) {
        this.wireName = wireName;
        this.sqlType = sqlType;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public static List<String> wireNames() {
        return WIRE_NAMES;
    }

    public int sqlType() {
        return sqlType;
    }

    @JsonCreator
    public static SqlParamType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "paramTypes value must not be blank. Supported types: " + supportedTypes());
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "date" -> date;
            case "timestamp" -> timestamp;
            case "timestamptz" -> timestamptz;
            case "uuid" -> uuid;
            case "integer" -> integer;
            case "decimal" -> decimal;
            default -> throw new IllegalArgumentException(
                    "Unsupported paramType '" + raw + "'. Supported types: " + supportedTypes());
        };
    }

    private static String supportedTypes() {
        return "date, timestamp, timestamptz, uuid, integer, decimal";
    }
}
