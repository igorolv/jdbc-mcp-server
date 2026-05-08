package ru.it_spectrum.ai.jdbc.mcp.usage;

import java.util.Objects;

/**
 * Composes and decomposes the textual primary key of a usage-catalog query record.
 *
 * <p>Format: {@code {dataSource}/{sourcePath}#{sourceUnit}}, with the {@code #unit} suffix
 * omitted when {@code sourceUnit} is empty. Examples (using a demo {@code SHOP} database with
 * {@code customer}, {@code customer_notes}, and {@code order} tables):
 *
 * <ul>
 *     <li>{@code SHOP/reports/customers/CustomerCard.xdo#CUST}</li>
 *     <li>{@code SHOP/manual/ad-hoc-2026-05-01} (no unit)</li>
 *     <li>{@code SHOP/com/example/shop/dao/OrderDao.java#findByCustomer}</li>
 * </ul>
 *
 * <p>Validation rules (enforced at ingest):
 * <ul>
 *     <li>{@code dataSource} — non-blank, no {@code /} or {@code #}</li>
 *     <li>{@code sourcePath} — non-blank, no {@code #}</li>
 *     <li>{@code sourceUnit} — may be blank or null; when present, no {@code /} or {@code #}</li>
 * </ul>
 *
 * <p>Encoding-by-rejection (rather than auto-escape) is intentional: callers should pass clean,
 * stable values, and any ambiguity surfaces immediately as an {@code argument} error.
 */
public final class UsageUid {

    private UsageUid() {
    }

    public static String build(String dataSource, String sourcePath, String sourceUnit) {
        validate(dataSource, sourcePath, sourceUnit);
        String unit = sourceUnit == null ? "" : sourceUnit;
        return unit.isEmpty()
                ? dataSource + "/" + sourcePath
                : dataSource + "/" + sourcePath + "#" + unit;
    }

    public static Parts parse(String uid) {
        Objects.requireNonNull(uid, "uid");
        if (uid.isBlank()) {
            throw new IllegalArgumentException("uid is blank");
        }
        String dataSource;
        String rest;
        int slash = uid.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("uid must start with '{dataSource}/...': " + uid);
        }
        dataSource = uid.substring(0, slash);
        rest = uid.substring(slash + 1);

        String sourcePath;
        String sourceUnit;
        int hash = rest.lastIndexOf('#');
        if (hash < 0) {
            sourcePath = rest;
            sourceUnit = "";
        } else {
            sourcePath = rest.substring(0, hash);
            sourceUnit = rest.substring(hash + 1);
        }
        if (sourcePath.isEmpty()) {
            throw new IllegalArgumentException("uid has empty sourcePath: " + uid);
        }
        return new Parts(dataSource, sourcePath, sourceUnit);
    }

    public static void validate(String dataSource, String sourcePath, String sourceUnit) {
        require(dataSource, "dataSource");
        require(sourcePath, "sourcePath");
        if (containsAny(dataSource, "/", "#")) {
            throw new IllegalArgumentException("dataSource must not contain '/' or '#': " + dataSource);
        }
        if (containsAny(sourcePath, "#")) {
            throw new IllegalArgumentException("sourcePath must not contain '#': " + sourcePath);
        }
        if (sourceUnit != null && !sourceUnit.isEmpty() && containsAny(sourceUnit, "/", "#")) {
            throw new IllegalArgumentException("sourceUnit must not contain '/' or '#': " + sourceUnit);
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required and must not be blank");
        }
    }

    private static boolean containsAny(String s, String... needles) {
        for (String needle : needles) {
            if (s.contains(needle)) return true;
        }
        return false;
    }

    public record Parts(String dataSource, String sourcePath, String sourceUnit) {
        public String sourceUnitNormalized() {
            return sourceUnit == null ? "" : sourceUnit;
        }
    }
}
