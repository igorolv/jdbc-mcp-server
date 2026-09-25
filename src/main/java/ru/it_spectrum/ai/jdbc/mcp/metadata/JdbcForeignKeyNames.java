package ru.it_spectrum.ai.jdbc.mcp.metadata;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Groups rows of an unnamed JDBC foreign key by their KEY_SEQ instead of their column names. */
final class JdbcForeignKeyNames {

    record Key(String childSchema, String childTable, String parentSchema, String parentTable,
               String name) {}

    private record Relation(String childSchema, String childTable,
                            String parentSchema, String parentTable, String parentKey) {}

    private static final class Group {
        final String name;
        int nextSequence = 2;

        Group(String name) {
            this.name = name;
        }
    }

    private final Map<Relation, Integer> ordinals = new HashMap<>();
    private final Map<Relation, List<Group>> groups = new HashMap<>();

    Key key(ResultSet row) throws SQLException {
        String name = name(row);
        return new Key(row.getString("FKTABLE_SCHEM"), row.getString("FKTABLE_NAME"),
                row.getString("PKTABLE_SCHEM"), row.getString("PKTABLE_NAME"), name);
    }

    private String name(ResultSet row) throws SQLException {
        String reported = row.getString("FK_NAME");
        if (reported != null && !reported.isBlank()) return reported;

        Relation relation = new Relation(row.getString("FKTABLE_SCHEM"), row.getString("FKTABLE_NAME"),
                row.getString("PKTABLE_SCHEM"), row.getString("PKTABLE_NAME"), row.getString("PK_NAME"));
        List<Group> candidates = groups.computeIfAbsent(relation, ignored -> new ArrayList<>());
        short sequence = row.getShort("KEY_SEQ");
        if (sequence > 1) {
            // Drivers may return all first columns before all second columns (SQLite does).
            // Match the earliest group waiting for this position in the same table/key pair.
            for (Group group : candidates) {
                if (group.nextSequence == sequence) {
                    group.nextSequence++;
                    return group.name;
                }
            }
        }
        int ordinal = ordinals.merge(relation, 1, Integer::sum);
        String base = "fk_anon_" + row.getString("FKCOLUMN_NAME");
        String name = ordinal == 1 ? base : base + "_" + ordinal;
        Group group = new Group(name);
        group.nextSequence = sequence + 1;
        candidates.add(group);
        return name;
    }
}
