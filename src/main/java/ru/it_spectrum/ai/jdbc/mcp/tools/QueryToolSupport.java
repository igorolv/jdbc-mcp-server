package ru.it_spectrum.ai.jdbc.mcp.tools;

/**
 * Shared text and helpers for the query tools. Both {@link QueryTools} (execution) and
 * {@link QueryAnalysisTools} (explain / plan / validate / inspect / lint / lineage) use these so
 * the parameter-binding guidance given to the LLM and the SQL normalization stay identical across
 * the two tool groups.
 */
final class QueryToolSupport {

    private QueryToolSupport() {
    }

    static final String BINDING_RULES =
            "Param binding: '?' placeholders -> 'params' (in order); ':name' placeholders -> 'namedParams'; " +
            "omit both if none. Never mix the two styles. ";

    static final String BINDING_EXAMPLES =
            "Example: 'WHERE status = :status' -> namedParams={status: 'PAID'}. ";

    /** Unescape the common backslash sequences an LLM may emit inside a JSON string before we run the SQL. */
    static String normalizeSql(String sql) {
        if (sql == null || sql.indexOf('\\') < 0) {
            return sql;
        }
        return sql
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
