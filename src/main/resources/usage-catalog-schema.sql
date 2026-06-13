-- Persistent SQLite usage-catalog index. Stores known SQL queries used by applications/reports
-- against the inspected database, with their business context. Local-only writes; the
-- inspected JDBC database is never touched by this index.

CREATE TABLE IF NOT EXISTS query (
    id                         INTEGER PRIMARY KEY,
    source_kind                TEXT NOT NULL,
    source_path                TEXT NOT NULL,
    source_unit                TEXT NOT NULL DEFAULT '',
    business_label             TEXT,
    business_domain            TEXT,
    raw_sql                    TEXT NOT NULL,
    normalized_sql             TEXT,
    parse_status               TEXT NOT NULL,
    parse_error                TEXT,
    source_meta_json           TEXT,
    ingested_at                TEXT NOT NULL,
    resolved_snapshot_version  BIGINT,
    UNIQUE (source_kind, source_path, source_unit)
);

CREATE INDEX IF NOT EXISTS idx_query_business_domain ON query(business_domain);

CREATE TABLE IF NOT EXISTS query_tag (
    query_id BIGINT NOT NULL REFERENCES query(id) ON DELETE CASCADE,
    tag      TEXT NOT NULL,
    PRIMARY KEY (query_id, tag)
);
CREATE INDEX IF NOT EXISTS idx_query_tag_tag ON query_tag(tag);

CREATE TABLE IF NOT EXISTS query_param (
    id                   INTEGER PRIMARY KEY,
    query_id             BIGINT NOT NULL REFERENCES query(id) ON DELETE CASCADE,
    ordinal              INTEGER NOT NULL,
    name                 TEXT,
    data_type            TEXT,
    default_value        TEXT,
    required             INTEGER NOT NULL DEFAULT 1,
    business_label       TEXT,
    business_description TEXT
);
CREATE INDEX IF NOT EXISTS idx_query_param_query ON query_param(query_id);

CREATE TABLE IF NOT EXISTS query_table (
    id                INTEGER PRIMARY KEY,
    query_id          BIGINT NOT NULL REFERENCES query(id) ON DELETE CASCADE,
    raw_name          TEXT NOT NULL,
    schema_resolved   TEXT,
    table_resolved    TEXT,
    alias             TEXT,
    role              TEXT NOT NULL,
    resolution_status TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_query_table_resolved ON query_table(schema_resolved, table_resolved);
CREATE INDEX IF NOT EXISTS idx_query_table_query ON query_table(query_id);

CREATE TABLE IF NOT EXISTS query_column (
    id              INTEGER PRIMARY KEY,
    query_id        BIGINT NOT NULL REFERENCES query(id) ON DELETE CASCADE,
    query_table_id  BIGINT REFERENCES query_table(id) ON DELETE SET NULL,
    schema_resolved TEXT,
    table_resolved  TEXT,
    column_name     TEXT NOT NULL,
    context         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_query_column_resolved ON query_column(schema_resolved, table_resolved, column_name);
CREATE INDEX IF NOT EXISTS idx_query_column_query ON query_column(query_id);

CREATE TABLE IF NOT EXISTS query_join (
    id            INTEGER PRIMARY KEY,
    query_id      BIGINT NOT NULL REFERENCES query(id) ON DELETE CASCADE,
    join_type     TEXT NOT NULL,
    left_schema   TEXT,
    left_table    TEXT,
    left_column   TEXT,
    right_schema  TEXT,
    right_table   TEXT,
    right_column  TEXT,
    on_text       TEXT,
    equality      INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_query_join_left  ON query_join(left_schema, left_table, left_column);
CREATE INDEX IF NOT EXISTS idx_query_join_right ON query_join(right_schema, right_table, right_column);

CREATE TABLE IF NOT EXISTS query_output (
    id                   INTEGER PRIMARY KEY,
    query_id             BIGINT NOT NULL REFERENCES query(id) ON DELETE CASCADE,
    alias                TEXT NOT NULL,
    source_expression    TEXT,
    business_label       TEXT,
    business_description TEXT,
    UNIQUE (query_id, alias)
);

CREATE TABLE IF NOT EXISTS query_output_column (
    id              INTEGER PRIMARY KEY,
    query_output_id BIGINT NOT NULL REFERENCES query_output(id) ON DELETE CASCADE,
    schema_resolved TEXT,
    table_resolved  TEXT,
    column_name     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_qoc_resolved ON query_output_column(schema_resolved, table_resolved, column_name);

CREATE TABLE IF NOT EXISTS query_field_usage (
    id                         INTEGER PRIMARY KEY,
    query_id                   BIGINT NOT NULL REFERENCES query(id) ON DELETE CASCADE,
    query_output_id            BIGINT REFERENCES query_output(id) ON DELETE SET NULL,
    business_object            TEXT,
    transformation_kind        TEXT NOT NULL,
    transformation_description TEXT,
    location_kind              TEXT,
    location_details_json      TEXT,
    headers_json               TEXT,
    confidence                 TEXT
);
CREATE INDEX IF NOT EXISTS idx_qfu_query ON query_field_usage(query_id);
CREATE INDEX IF NOT EXISTS idx_qfu_kind  ON query_field_usage(transformation_kind);
