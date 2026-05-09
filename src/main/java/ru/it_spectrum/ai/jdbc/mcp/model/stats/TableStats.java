package ru.it_spectrum.ai.jdbc.mcp.model.stats;

public record TableStats(
        String schema,
        String table,
        String table_name,
        boolean found,
        Object relkind,
        Object estimated_rows,
        Object total_size_bytes,
        Object table_size_bytes,
        Object indexes_size_bytes,
        Object toast_size_bytes,
        Object live_tuples,
        Object dead_tuples,
        Object dead_tuple_pct,
        Object seq_scan,
        Object seq_tup_read,
        Object idx_scan,
        Object idx_tup_fetch,
        Object tup_ins,
        Object tup_upd,
        Object tup_del,
        Object last_vacuum,
        Object last_autovacuum,
        Object last_analyze,
        Object last_autoanalyze,
        Object blocks,
        Object empty_blocks,
        Object avg_row_len,
        Object chain_count,
        Object last_analyzed,
        Object sample_size,
        Object partitioned,
        Object temporary,
        Object global_stats,
        Object user_stats,
        Object compression,
        Object create_date,
        Object modify_date,
        Object is_memory_optimized,
        Object temporal_type_desc,
        Object is_filetable,
        Object segment_bytes,
        String segment_bytes_error,
        String error
) {
    public static TableStats notFound(String schema, String table) {
        return new TableStats(
                schema, table, null, false,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public static TableStats error(String schema, String table, String error) {
        return new TableStats(
                schema, table, null, false,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, error);
    }
}
