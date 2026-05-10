package ru.it_spectrum.ai.jdbc.mcp.model.context;

import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Constraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Trigger;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.TableEvidenceProfile;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.TableStats;

import java.util.List;
import java.util.Map;

public record CompactTable(
        String schema,
        String name,
        String type,
        String remarks,
        List<CompactColumn> columns,
        PrimaryKey primaryKey,
        List<Constraint> constraints,
        Map<String, List<String>> allowedValues,
        List<ForeignKey> foreignKeys,
        List<CompactIndex> indexes,
        List<Trigger> triggers,
        TableStats stats,
        TableEvidenceProfile evidence
) implements ContextTable {
    public CompactTable withEvidence(TableEvidenceProfile evidence) {
        return new CompactTable(
                schema, name, type, remarks, columns, primaryKey, constraints,
                allowedValues, foreignKeys, indexes, triggers, stats, evidence);
    }

    public record CompactColumn(
            String name,
            String type,
            boolean nullable,
            Boolean isPrimaryKey,
            Boolean foreignKey,
            Boolean indexed
    ) {
    }

    public record CompactIndex(
            String name,
            boolean unique,
            List<String> columns
    ) {
    }
}
