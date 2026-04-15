package ru.it_spectrum.ai.jdbc.mcp.format;

public enum OutputFormat {
    JSON, MARKDOWN, CSV;

    public static OutputFormat parse(String raw) {
        if (raw == null || raw.isBlank()) return JSON;
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case "json" -> JSON;
            case "md", "markdown", "table" -> MARKDOWN;
            case "csv" -> CSV;
            default -> throw new IllegalArgumentException(
                    "Unknown output format '" + raw + "'. Supported: json, markdown, csv");
        };
    }
}
