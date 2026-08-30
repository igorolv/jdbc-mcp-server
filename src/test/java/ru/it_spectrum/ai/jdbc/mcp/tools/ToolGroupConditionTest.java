package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the {@code jdbc-mcp.tools.*} group toggles: each tool {@code @Service} is gated by
 * {@code @ConditionalOnProperty}. All groups are on by default (so the manifest is unchanged out of
 * the box); turning a group off removes its tools from the MCP {@code tools/list} manifest.
 */
class ToolGroupConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Mocks.class, Tools.class);

    @Test
    void allGroupsAreExposedByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MetadataTools.class);
            assertThat(ctx).hasSingleBean(QueryTools.class);
            assertThat(ctx).hasSingleBean(SampleTools.class);
            assertThat(ctx).hasSingleBean(DistributionTools.class);
            assertThat(ctx).hasSingleBean(ConnectionTools.class);
        });
    }

    @Test
    void connectionDiscoveryCanBeTurnedOff() {
        runner.withPropertyValues("jdbc-mcp.tools.connections=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ConnectionTools.class);
                    assertThat(ctx).hasSingleBean(MetadataTools.class);
                });
    }

    @Test
    void disablingAGroupRemovesIt() {
        runner.withPropertyValues("jdbc-mcp.tools.distribution=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(DistributionTools.class);
                    assertThat(ctx).hasSingleBean(MetadataTools.class); // others stay on
                });
    }

    @Test
    void disablingMultipleGroupsLeavesOnlyTheRest() {
        runner.withPropertyValues(
                        "jdbc-mcp.tools.metadata=false",
                        "jdbc-mcp.tools.sample=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(MetadataTools.class);
                    assertThat(ctx).doesNotHaveBean(SampleTools.class);
                    assertThat(ctx).hasSingleBean(QueryTools.class);
                    assertThat(ctx).hasSingleBean(DistributionTools.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MetadataTools.class, QueryTools.class, SampleTools.class, DistributionTools.class,
            ConnectionTools.class})
    static class Tools {
    }

    @Configuration(proxyBeanMethods = false)
    static class Mocks {
        @Bean ConnectionRegistry connectionRegistry() { return mock(ConnectionRegistry.class); }
        @Bean JsonResponses jsonResponses() { return mock(JsonResponses.class); }
        @Bean ToolErrors toolErrors() { return mock(ToolErrors.class); }
    }
}
