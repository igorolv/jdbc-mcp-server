package ru.it_spectrum.ai.jdbc.mcp.usage;

import java.util.Objects;

/**
 * Composes and decomposes the textual primary key of a usage-catalog query record.
 *
 * <p>Format: {@code {sourceKind}/{sourcePath}#{sourceUnit}}, with the {@code #unit} suffix
 * omitted when {@code sourceUnit} is empty. Examples:
 *
 * <ul>
 *     <li>{@code database-view/native/view/SHOP.CUSTOMER_DETAILS}</li>
 *     <li>{@code dao/com/example/shop/dao/OrderDao.java#findByCustomer}</li>
 *     <li>{@code manual/examples/manual/customer-count} (no unit)</li>
 * </ul>
 *
 * <p>Validation rules (enforced at ingest):
 * <ul>
 *     <li>{@code sourceKind} — non-blank, no {@code /} or {@code #}</li>
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

    public static String build(String sourceKind, String sourcePath, String sourceUnit) {
        validate(sourceKind, sourcePath, sourceUnit);
        String unit = sourceUnit == null ? "" : sourceUnit;
        return unit.isEmpty()
                ? sourceKind + "/" + sourcePath
                : sourceKind + "/" + sourcePath + "#" + unit;
    }

    public static Parts parse(String uid) {
        Objects.requireNonNull(uid, "uid");
        if (uid.isBlank()) {
            throw new IllegalArgumentException("uid is blank");
        }
        String sourceKind;
        String rest;
        int slash = uid.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("uid must start with '{sourceKind}/...': " + uid);
        }
        sourceKind = uid.substring(0, slash);
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
        return new Parts(sourceKind, sourcePath, sourceUnit);
    }

    public static void validate(String sourceKind, String sourcePath, String sourceUnit) {
        require(sourceKind, "sourceKind");
        require(sourcePath, "sourcePath");
        if (containsAny(sourceKind, "/", "#")) {
            throw new IllegalArgumentException("sourceKind must not contain '/' or '#': " + sourceKind);
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

    public record Parts(String sourceKind, String sourcePath, String sourceUnit) {
        public String sourceUnitNormalized() {
            return sourceUnit == null ? "" : sourceUnit;
        }
    }
}
