#!/usr/bin/env python3
"""
Convert Oracle USER_QUERY table rows to jdbc-mcp-server
QueryUsageRecord format.

Connects to an Oracle database using oracledb, queries SSV.USER_QUERY
(joined with SSV.SUBSYSTEM for human-readable domain names), parses the
XML stored in UQ_DEF to extract SQL text and bind parameters, and emits
one QueryUsageRecord per row.

Usage:
  python convert.py [--output records.json]

Env vars (same as LiveOracleIntegrationSchemaTest):
  LIVE_ORACLE_URL       JDBC URL (e.g. jdbc:oracle:thin:@//host:1521/service)
  LIVE_ORACLE_USERNAME  Database username
  LIVE_ORACLE_PASSWORD  Database password
  LIVE_ORACLE_SCHEMA    Schema name (optional — defaults to username upper-cased)
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
from xml.etree import ElementTree as ET

try:
    import oracledb
except ImportError:
    oracledb = None

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
SCHEMA_VERSION = 1
SQL_STRING_RE = re.compile(r"'(?:''|[^'])*'")
BIND_PARAMETER_RE = re.compile(r"(?<!:):([A-Za-z_][A-Za-z0-9_$#]*)")

logger = logging.getLogger("user-query-convert")


# ---------------------------------------------------------------------------
# SQL text helpers
# ---------------------------------------------------------------------------
def strip_sql_string_literals(sql_text: str) -> str:
    return SQL_STRING_RE.sub(lambda m: " " * len(m.group(0)), sql_text)


def find_bind_parameters(sql_text: str) -> list[str]:
    params = []
    seen: set[str] = set()
    cleaned = strip_sql_string_literals(sql_text)
    for m in BIND_PARAMETER_RE.finditer(cleaned):
        name = m.group(1)
        if name.lower() not in seen:
            seen.add(name.lower())
            params.append(name)
    return params


# ---------------------------------------------------------------------------
# UQ_DEF XML parser
# ---------------------------------------------------------------------------
def parse_uq_def(uq_def_xml: str) -> dict:
    """Parse the XML inside UQ_DEF column.

    Returns dict with keys: sql_text (str), variables (list[dict]), row_count_disable (bool).
    """
    result: dict = {"sql_text": "", "variables": [], "row_count_disable": False}

    if not uq_def_xml or not uq_def_xml.strip():
        return result

    # Clean XML: the data may have \n instead of line-breaks, and encoding pi differences
    xml_text = uq_def_xml.strip()
    # Remove XML declaration to avoid encoding parse issues
    xml_text = re.sub(r"<\?xml[^?]*\?>", "", xml_text, count=1)
    # Unescape XML entities from the data
    xml_text = xml_text.replace("\\/", "/")
    # Fix common escape artifacts in the sample data
    xml_text = xml_text.replace("\\n", "\n").replace("\\r", "\r")

    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as e:
        logger.warning("XML parse error in UQ_DEF: %s", e)
        return result

    text_elem = root.find("text")
    if text_elem is not None and text_elem.text:
        result["sql_text"] = text_elem.text.strip()

    row_count_elem = root.find("rowCountDisable")
    if row_count_elem is not None and row_count_elem.text:
        result["row_count_disable"] = row_count_elem.text.strip().lower() == "true"

    for var_elem in root.findall("var"):
        var: dict = {
            "name": var_elem.get("NAME", ""),
            "label": var_elem.get("LABEL", ""),
            "type": var_elem.get("TYPE", ""),
            "kind": var_elem.get("KIND", ""),
        }
        required = var_elem.get("REQUIRED", "false")
        var["required"] = required.lower() == "true"
        lov_id = var_elem.get("LOV_ID", "")
        if lov_id:
            var["lov_id"] = lov_id
        lov_key = var_elem.get("LOV_KEY_ATTR_NAME", "")
        if lov_key:
            var["lov_key_attr"] = lov_key
        lov_label = var_elem.get("LOV_LABEL_ATTR_NAME", "")
        if lov_label:
            var["lov_label_attr"] = lov_label
        sskv_var = var_elem.get("SSKV_VAR", "")
        if sskv_var:
            var["sskv_var"] = sskv_var

        result["variables"].append(var)

    return result


# ---------------------------------------------------------------------------
# Database connection
# ---------------------------------------------------------------------------
def get_env_or_fail(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        logger.error("Environment variable %s is not set", name)
        raise SystemExit(1)
    return value


def build_dsn(url: str) -> str:
    """Convert a JDBC Oracle URL to an oracledb DSN string.

    Supports:
      jdbc:oracle:thin:@//host:port/service
      jdbc:oracle:thin:@host:port:SID
    """
    # jdbc:oracle:thin:@//host:port/service
    m = re.match(r"jdbc:oracle:thin:@//([^:/]+(?::\d+)?)/(.+)", url)
    if m:
        host, port = _split_host_port(m.group(1))
        service = m.group(2)
        return f"(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST={host})(PORT={port}))(CONNECT_DATA=(SERVICE_NAME={service})))"

    # jdbc:oracle:thin:@host:port:SID
    m = re.match(r"jdbc:oracle:thin:@([^:/]+(?::\d+)?):(.+)", url)
    if m:
        host, port = _split_host_port(m.group(1))
        sid = m.group(2)
        return f"(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST={host})(PORT={port}))(CONNECT_DATA=(SID={sid})))"

    # Fallback: strip jdbc:oracle:thin:@ prefix
    fallback = re.sub(r"^jdbc:oracle:thin:@", "", url)
    return fallback


def _split_host_port(host_port: str) -> tuple[str, str]:
    if ":" in host_port:
        host, port = host_port.rsplit(":", 1)
        return host, port
    return host_port, "1521"


def resolve_schema(username: str) -> str:
    """Determine the database schema, same logic as LiveOracleIntegrationSchemaTest."""
    explicit = os.environ.get("LIVE_ORACLE_SCHEMA", "").strip()
    if explicit:
        return explicit.upper()
    return username.upper()


def fetch_user_queries(
    url: str,
    username: str,
    password: str,
    schema: str,
) -> list[dict]:
    """Connect to Oracle and fetch all rows from USER_QUERY
    joined with SUBSYSTEM.
    """
    if oracledb is None:
        logger.error(
            "oracledb is not installed. Install it with:\n  pip install oracledb"
        )
        raise SystemExit(1)

    dsn = build_dsn(url)
    logger.info("Connecting to %s as %s, schema=%s ...", dsn, username, schema)

    try:
        conn = oracledb.connect(
            user=username,
            password=password,
            dsn=dsn,
        )
    except oracledb.Error as e:
        logger.error("Failed to connect: %s", e)
        raise SystemExit(1)

    # Validate schema name (stops injection — identifiers only)
    if not re.match(r"^[A-Za-z_][A-Za-z0-9_$#]*$", schema):
        logger.error("Invalid schema name: %s", schema)
        raise SystemExit(1)

    sql = f"""
        SELECT
            uq.USER_QUERY_ID,
            uq.SUBSYSTEM_CODE,
            uq.UQ_NAME,
            uq.UQ_DEF,
            uq.UQ_LOV_FLAG,
            uq.UQ_TYPE_ENUM,
            uq.UQ_HEADER_TMPL,
            uq.UQ_ARCHIVE_FLAG,
            uq.MASTER_USER_QUERY_ID,
            ss.SUBSYSTEM_NAME
        FROM {schema}.USER_QUERY uq
        LEFT JOIN {schema}.SUBSYSTEM ss ON ss.SUBSYSTEM_CODE = uq.SUBSYSTEM_CODE
        ORDER BY uq.SUBSYSTEM_CODE, uq.USER_QUERY_ID
    """
    rows: list[dict] = []
    try:
        with conn.cursor() as curs:
            curs.execute(sql)
            columns = [desc[0].lower() for desc in curs.description]
            for row_values in curs:
                row = dict(zip(columns, row_values))
                # read CLOB
                if row.get("uq_def") is not None:
                    clob = row["uq_def"]
                    try:
                        row["uq_def"] = clob.read()
                    except Exception:
                        pass
                rows.append(row)
    except oracledb.Error as e:
        logger.error("Query failed: %s", e)
        raise SystemExit(1)
    finally:
        conn.close()

    logger.info("Fetched %d rows from %s.USER_QUERY", len(rows), schema)
    return rows


# ---------------------------------------------------------------------------
# Record builder
# ---------------------------------------------------------------------------
def determine_sql_type(sql_text: str) -> str:
    """Guess whether the SQL is a SELECT, WITH, or other."""
    stripped = sql_text.strip().upper()
    if stripped.startswith("WITH"):
        return "cte"
    if stripped.startswith("SELECT"):
        return "select"
    return "other"


def row_to_record(row: dict) -> dict:
    """Convert one USER_QUERY database row to a QueryUsageRecord dict."""
    uq_name = (row.get("uq_name") or "").strip()
    subsystem_code = (row.get("subsystem_code") or "").strip()
    subsystem_name = (row.get("subsystem_name") or "").strip()

    # Parse UQ_DEF
    uq_def = row.get("uq_def") or ""
    parsed = parse_uq_def(str(uq_def))
    sql_text = parsed["sql_text"]

    # Build parameters
    parameters = _build_parameters(parsed["variables"], sql_text)

    # Build tags
    tags = _build_tags(row, subsystem_code)

    # Source path — use primary key as stable identifier
    source_path = str(row.get("user_query_id"))

    # Business domain — prefer resolved subsystem name
    business_domain = subsystem_name or subsystem_code or None

    record: dict = {
        "schemaVersion": SCHEMA_VERSION,
        "source": {
            "kind": "user-query",
            "path": source_path,
            "unit": uq_name,
        },
        "businessLabel": uq_name,
        "sql": sql_text,
    }

    if business_domain:
        record["businessDomain"] = business_domain

    if tags:
        record["businessTags"] = tags

    if parameters:
        record["parameters"] = parameters

    # sourceMeta
    meta: dict = {
        "adapter": "user-query",
        "adapterVersion": "1.0.0",
        "userQueryId": row.get("user_query_id"),
        "subsystemCode": subsystem_code,
    }

    if subsystem_name:
        meta["subsystemName"] = subsystem_name

    uq_type = row.get("uq_type_enum")
    if uq_type is not None:
        meta["uqTypeEnum"] = uq_type

    uq_lov_flag = row.get("uq_lov_flag")
    if uq_lov_flag is not None:
        meta["uqLovFlag"] = bool(uq_lov_flag)

    archive_flag = row.get("uq_archive_flag")
    if archive_flag is not None:
        meta["uqArchiveFlag"] = bool(archive_flag)

    master_id = row.get("master_user_query_id")
    if master_id is not None:
        meta["masterUserQueryId"] = master_id

    header_tmpl = row.get("uq_header_tmpl")
    if header_tmpl:
        meta["uqHeaderTmpl"] = header_tmpl

    sql_type = determine_sql_type(sql_text)
    if sql_type:
        meta["sqlType"] = sql_type
    meta["rowCountDisable"] = parsed["row_count_disable"]

    record["sourceMeta"] = meta

    return record


def _build_parameters(variables: list[dict], sql_text: str) -> list[dict]:
    """Build parameters list from declared <var> elements and detected
    bind variables in SQL text.
    """
    seen_names: set[str] = set()
    result: list[dict] = []

    # Add declared variables first
    for var in variables:
        name = (var.get("name") or "").strip()
        if not name or name.lower() in seen_names:
            continue
        seen_names.add(name.lower())

        entry: dict = {"name": name}

        ptype = (var.get("type") or "").strip()
        if ptype:
            entry["dataType"] = ptype

        label = (var.get("label") or "").strip()
        if label:
            entry["businessLabel"] = label

        required = var.get("required", False)
        if required:
            entry["required"] = True

        result.append(entry)

    # Add bind parameters not declared as <var>
    sql_binds = find_bind_parameters(sql_text)
    for bp in sql_binds:
        if bp.lower() not in seen_names:
            seen_names.add(bp.lower())
            result.append({"name": bp, "dataType": "string"})

    return result


def _build_tags(row: dict, subsystem_code: str) -> list[str]:
    tags = {"user-query"}

    if subsystem_code:
        tags.add(subsystem_code.lower())

    lov_flag = row.get("uq_lov_flag")
    if lov_flag == 1:
        tags.add("lov")

    type_enum = row.get("uq_type_enum")
    if type_enum == 1:
        tags.add("sql-record")
    elif type_enum == 2:
        tags.add("ref-cursor")

    archive_flag = row.get("uq_archive_flag")
    if archive_flag == 1:
        tags.add("archive")

    master_id = row.get("master_user_query_id")
    if master_id is not None:
        tags.add("child-query")

    return sorted(tags)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(
        description="Convert Oracle USER_QUERY table to jdbc-mcp-server QueryUsageRecord format.",
    )
    parser.add_argument(
        "--output", "-o",
        default="user-query.json",
        help="Output JSON file (default: user-query.json)",
    )
    parser.add_argument(
        "--pretty", "-p",
        action="store_true",
        default=True,
        help="Pretty-print output (default: true)",
    )
    parser.add_argument(
        "--url",
        help="Oracle JDBC URL (overrides LIVE_ORACLE_URL)",
    )
    parser.add_argument(
        "--username",
        help="Database username (overrides LIVE_ORACLE_USERNAME)",
    )
    parser.add_argument(
        "--password",
        help="Database password (overrides LIVE_ORACLE_PASSWORD)",
    )
    parser.add_argument(
        "--schema",
        help="Database schema (overrides LIVE_ORACLE_SCHEMA; defaults to username upper-cased)",
    )
    args = parser.parse_args()

    # Try CLI args first, then env vars
    url = args.url or get_env_or_fail("LIVE_ORACLE_URL")
    username = args.username or get_env_or_fail("LIVE_ORACLE_USERNAME")
    password = args.password or get_env_or_fail("LIVE_ORACLE_PASSWORD")

    # Schema: CLI --schema > LIVE_ORACLE_SCHEMA > username upper-cased
    if args.schema:
        schema = args.schema.upper()
    else:
        schema = resolve_schema(username)

    rows = fetch_user_queries(url, username, password, schema)

    records = []
    for row in rows:
        record = row_to_record(row)
        records.append(record)

    indent = 2 if args.pretty else None
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=indent)

    logger.info("Written %d records to %s", len(records), args.output)
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    raise SystemExit(main())
