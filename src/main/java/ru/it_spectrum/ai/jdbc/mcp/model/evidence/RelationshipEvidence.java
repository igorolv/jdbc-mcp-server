package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * Bundles the three evidence layers describing a single relationship between two tables:
 * the declared schema (catalog FK), observed query usage (equi-joins seen in stored queries),
 * and semantic usage (business terms shared by queries that touch both tables).
 *
 * <p>Each layer is independently nullable. Empty semantic usage is normalized to {@code null}
 * so downstream JSON does not carry empty buckets.
 */
@Schema(description = "Combined evidence for a relationship from declared schema, observed joins, and semantic usage.")
public record RelationshipEvidence(
        @Schema(description = "Evidence from declared database metadata, normally a foreign key.", nullable = true)
        DeclaredSchemaEdgeEvidence declaredSchema,
        @Schema(description = "Evidence from known SQL queries in the usage catalog.", nullable = true)
        ObservedQueryEdgeEvidence observedQuery,
        @Schema(description = "Evidence from business labels, domains, tags, outputs, and field usage in the catalog.", nullable = true)
        SemanticEdgeEvidence semanticUsage
) {
    public RelationshipEvidence {
        if (semanticUsage != null && semanticUsage.isEmpty()) {
            semanticUsage = null;
        }
    }

    public boolean isEmpty() {
        return declaredSchema == null && observedQuery == null
                && semanticUsage == null;
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
