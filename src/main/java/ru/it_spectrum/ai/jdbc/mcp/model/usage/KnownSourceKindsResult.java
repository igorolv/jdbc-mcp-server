package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record KnownSourceKindsResult(
        List<KindEntry> kinds
) {
    public record KindEntry(String kind, int count) {
    }
}
