package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bundles the three evidence layers describing a single relationship between two tables:
 * the declared schema (catalog FK), observed query usage (equi-joins seen in stored queries),
 * and semantic usage (business terms shared by queries that touch both tables).
 *
 * <p>Each layer is independently nullable. Empty layers are omitted from {@link #toMap()} so
 * downstream JSON does not carry empty buckets.
 */
public record RelationshipEvidence(
        DeclaredSchemaEdgeEvidence declaredSchema,
        ObservedQueryEdgeEvidence observedQuery,
        SemanticEdgeEvidence semanticUsage
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (declaredSchema != null) out.put("declaredSchema", declaredSchema.toMap());
        if (observedQuery != null) out.put("observedQuery", observedQuery.toMap());
        if (semanticUsage != null && !semanticUsage.isEmpty()) {
            out.put("semanticUsage", semanticUsage.toMap());
        }
        return out;
    }

    public boolean isEmpty() {
        return declaredSchema == null && observedQuery == null
                && (semanticUsage == null || semanticUsage.isEmpty());
    }

    public static final class Builder {
        public DeclaredSchemaEdgeEvidence declaredSchema;
        public ObservedQueryEdgeEvidence observedQuery;
        public SemanticEdgeEvidence semanticUsage;

        public RelationshipEvidence build() {
            return new RelationshipEvidence(declaredSchema, observedQuery, semanticUsage);
        }
    }
}
