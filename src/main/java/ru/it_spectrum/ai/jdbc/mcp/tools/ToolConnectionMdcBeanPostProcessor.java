package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.MDC;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

/**
 * Adds the selected connection to the logging MDC for the complete lifetime of every MCP tool
 * invocation. The scope starts before the tool's own first log entry and is restored in a
 * {@code finally} block, so pooled MCP threads cannot leak a connection name into the next call.
 */
@Component
final class ToolConnectionMdcBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

    static final String MDC_KEY = "connection";
    private static final String CONNECTION_PARAMETER = "connection";

    @Override
    public int getOrder() {
        // The Spring AI annotation scanner must register the proxy, not the unwrapped bean.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> beanClass = AopUtils.getTargetClass(bean);
        if (!hasMcpToolMethod(beanClass)) {
            return bean;
        }

        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), beanClass);
            String connection = connectionArgument(method, invocation.getArguments());
            if (connection == null) {
                return invocation.proceed();
            }

            String previous = MDC.get(MDC_KEY);
            MDC.put(MDC_KEY, connection);
            try {
                return invocation.proceed();
            } finally {
                restore(previous);
            }
        });
        return proxyFactory.getProxy();
    }

    private static boolean hasMcpToolMethod(Class<?> beanClass) {
        return Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(beanClass))
                .anyMatch(method -> method.isAnnotationPresent(McpTool.class));
    }

    private static String connectionArgument(Method method, Object[] arguments) {
        if (!method.isAnnotationPresent(McpTool.class)) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(CONNECTION_PARAMETER)
                    && arguments[i] instanceof String value
                    && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void restore(String previous) {
        if (previous == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previous);
        }
    }
}
