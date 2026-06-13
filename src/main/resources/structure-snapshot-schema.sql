-- Persistent structure snapshot (same <catalog>.db file as the usage catalog).
-- Hybrid: detail_json is the assembly source of truth for describeTable;
-- snapshot_column / snapshot_foreign_key are a flat PROJECTION for joins with the
-- usage catalog and for searchObjects/findTablesByName -- written on build, never read
-- back to reassemble a TableDescription. Cache-forever: rows live until explicit
-- invalidate or file deletion.
--
-- 'schema' is stored non-null ('' sentinel) to mirror how MetadataService binds
-- schema == null ? "". catalog_meta uses explicit meta_key / meta_value names.

CREATE TABLE IF NOT EXISTS catalog_meta (
    meta_key   TEXT PRIMARY KEY,
    meta_value TEXT
);   -- format_version, db_identity (advisory), snapshot_version, structure_built_at,
     -- structure_schemas (CSV of schemas actually covered by the snapshot)

CREATE TABLE IF NOT EXISTS snapshot_table (
    schema      TEXT NOT NULL,
    name        TEXT NOT NULL,
    type        TEXT,
    remarks     TEXT,
    detail_json TEXT NOT NULL,            -- full TableDescription, JSON
    PRIMARY KEY (schema, name)
);

CREATE TABLE IF NOT EXISTS snapshot_column (
    schema         TEXT NOT NULL,
    table_name     TEXT NOT NULL,
    ordinal        INTEGER,
    name           TEXT NOT NULL,
    type_name      TEXT,
    size           INTEGER,
    decimal_digits INTEGER,
    nullable       INTEGER,
    column_default TEXT,
    remarks        TEXT,
    auto_increment INTEGER,
    PRIMARY KEY (schema, table_name, name)
);
CREATE INDEX IF NOT EXISTS idx_snap_col_tbl ON snapshot_column(schema, table_name);

CREATE TABLE IF NOT EXISTS snapshot_foreign_key (
    schema      TEXT NOT NULL,
    table_name  TEXT NOT NULL,
    name        TEXT,
    ordinal     INTEGER,
    column_name TEXT NOT NULL,
    ref_schema  TEXT,
    ref_table   TEXT NOT NULL,
    ref_column  TEXT
);
CREATE INDEX IF NOT EXISTS idx_snap_fk_src ON snapshot_foreign_key(schema, table_name);
CREATE INDEX IF NOT EXISTS idx_snap_fk_ref ON snapshot_foreign_key(ref_schema, ref_table);

CREATE TABLE IF NOT EXISTS snapshot_view (
    schema     TEXT NOT NULL,
    name       TEXT NOT NULL,
    definition TEXT,
    PRIMARY KEY (schema, name)
);

CREATE TABLE IF NOT EXISTS snapshot_routine (
    schema TEXT NOT NULL,
    name   TEXT NOT NULL,
    type   TEXT NOT NULL,
    source TEXT,
    PRIMARY KEY (schema, name, type)
);

CREATE TABLE IF NOT EXISTS snapshot_sequence (
    schema TEXT NOT NULL,
    name   TEXT NOT NULL,
    PRIMARY KEY (schema, name)
);

CREATE TABLE IF NOT EXISTS snapshot_trigger (
    schema     TEXT NOT NULL,
    table_name TEXT NOT NULL DEFAULT '',
    name       TEXT NOT NULL,
    timing     TEXT,
    events     TEXT,                       -- CSV (Trigger.events)
    enabled    INTEGER,
    definition TEXT,                        -- body always stored
    PRIMARY KEY (schema, table_name, name)
);
CREATE INDEX IF NOT EXISTS idx_snap_trg_tbl ON snapshot_trigger(schema, table_name);
