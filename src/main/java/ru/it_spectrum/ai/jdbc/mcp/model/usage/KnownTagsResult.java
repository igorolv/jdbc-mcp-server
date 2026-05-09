package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record KnownTagsResult(
        String dataSource,
        List<TagEntry> tags
) {
    public record TagEntry(String tag, int count) {
    }
}