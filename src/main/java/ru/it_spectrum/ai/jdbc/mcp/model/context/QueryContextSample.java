package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;

public record QueryContextSample(
        List<String> columns,
        List<Map<String, Object>> rows,
        Integer rowCount,
        String sampleError
) {
}
