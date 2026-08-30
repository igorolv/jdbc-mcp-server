package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolConnectionMdcBeanPostProcessorTest {

    private final ToolConnectionMdcBeanPostProcessor processor =
            new ToolConnectionMdcBeanPostProcessor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesConnectionDuringToolCallAndRemovesItAfterward() throws Exception {
        ProbeTool target = new ProbeTool();
        Object proxy = processor.postProcessAfterInitialization(target, "probeTool");

        toolMethod().invoke(proxy, "orders");

        assertThat(target.observedConnection).isEqualTo("orders");
        assertThat(MDC.get(ToolConnectionMdcBeanPostProcessor.MDC_KEY)).isNull();
    }

    @Test
    void restoresAnOuterMdcValue() throws Exception {
        MDC.put(ToolConnectionMdcBeanPostProcessor.MDC_KEY, "outer");
        ProbeTool target = new ProbeTool();
        Object proxy = processor.postProcessAfterInitialization(target, "probeTool");

        toolMethod().invoke(proxy, "billing");

        assertThat(target.observedConnection).isEqualTo("billing");
        assertThat(MDC.get(ToolConnectionMdcBeanPostProcessor.MDC_KEY)).isEqualTo("outer");
    }

    @Test
    void leavesToolsWithoutAConnectionParameterAtProcessScope() throws Exception {
        ProbeTool target = new ProbeTool();
        Object proxy = processor.postProcessAfterInitialization(target, "probeTool");
        Method method = ProbeTool.class.getDeclaredMethod("listConnections");

        method.invoke(proxy);

        assertThat(target.observedConnection).isNull();
        assertThat(MDC.get(ToolConnectionMdcBeanPostProcessor.MDC_KEY)).isNull();
    }

    @Test
    void proxyRemainsDiscoverableByTheSpringAiToolProvider() {
        Object proxy = processor.postProcessAfterInitialization(new ProbeTool(), "probeTool");

        var specifications = SyncMcpAnnotationProviders.toolSpecifications(List.of(proxy));

        assertThat(specifications)
                .extracting(specification -> specification.tool().name())
                .containsExactly("call", "listConnections");
    }

    private static Method toolMethod() throws NoSuchMethodException {
        return ProbeTool.class.getDeclaredMethod("call", String.class);
    }

    public static class ProbeTool {
        private String observedConnection;

        @McpTool
        public String call(String connection) {
            observedConnection = MDC.get(ToolConnectionMdcBeanPostProcessor.MDC_KEY);
            return observedConnection;
        }

        @McpTool
        public String listConnections() {
            observedConnection = MDC.get(ToolConnectionMdcBeanPostProcessor.MDC_KEY);
            return observedConnection;
        }
    }
}
