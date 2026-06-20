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
            "Binding rules: if SQL has no placeholders, omit both 'params' and 'namedParams'. " +
            "If SQL contains '?', pass values in 'params' only, in placeholder order. " +
            "If SQL contains named placeholders in the form ':paramName' such as ':userId' or ':status', " +
            "pass values in 'namedParams' only. " +
            "Never mix '?' and named placeholders in the same SQL statement, and never pass both argument styles. ";

    static final String BINDING_EXAMPLES =
            "Examples: positional -> sql='SELECT * FROM orders WHERE customer_id = ? AND status = ?', " +
            "params=[123, 'PAID']; named -> sql='SELECT * FROM orders WHERE customer_id = :customerId " +
            "AND status = :status', namedParams={customerId: 123, status: 'PAID'}. ";

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
