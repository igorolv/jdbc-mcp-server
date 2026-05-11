package ru.it_spectrum.ai.jdbc.mcp.integration;

import ru.it_spectrum.ai.jdbc.mcp.tools.BenchmarkTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.DistributionTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.MetadataTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.QueryTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SampleTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SchemaContextTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.StatsTools;

record IntegrationTestContext(
        String schema,
        QueryTools queryTools,
        MetadataTools metadataTools,
        SampleTools sampleTools,
        StatsTools statsTools,
        SchemaContextTools schemaContextTools,
        DistributionTools distributionTools,
        BenchmarkTools benchmarkTools
) {
}
