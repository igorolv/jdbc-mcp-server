package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record ObservedRelationshipsResult(
        String schema,
        String table,
        int minSupport,
        List<Relationship> relationships,
        int count
) {
    public record Relationship(
            SchemaRef left,
            SchemaRef right,
            int support,
            List<QuerySourceRef> sourceRefs
    ) {
        public Relationship {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }

    public record SchemaRef(
            String schema,
            String table,
            String column
    ) {
    }
}