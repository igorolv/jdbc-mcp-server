package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;

public final class ToolLogger {

    private ToolLogger() {}

    public static void completed(Logger log, String toolName, long startNanos) {
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("Tool completed: {} (elapsed: {}ms)", toolName, elapsed);
    }

    public static void failed(Logger log, String toolName, long startNanos, String error) {
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        log.warn("Tool failed: {} (elapsed: {}ms): {}", toolName, elapsed, error);
    }
}