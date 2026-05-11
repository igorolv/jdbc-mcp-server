#!/usr/bin/env python3
"""
Convert Oracle ADF ViewObject XML definitions to jdbc-mcp-server
QueryUsageRecord format.

Scans a project tree for ADF ViewObject XML files under any `src/`
directory. For each one it extracts SQL (from `<SQLQuery>` or from
`SelectList`+`FromList`+`Where` attributes), maps output columns
(ViewAttribute / AliasName), resolves business labels from
ResourceBundle .properties files, and emits one QueryUsageRecord
per ViewObject.

Usage:
  python convert.py <path-to-adf-project> [--output records.json]
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
SCHEMA_VERSION = 1

NS_BC4J = "http://xmlns.oracle.com/bc4j"

# Regex to find named bind parameters like :paramName or :P_BIND_VAL
BIND_PARAMETER_RE = re.compile(r"(?<!':)(?<!')[:]([A-Za-z_][A-Za-z0-9_]*)")
SQL_STRING_RE = re.compile(r"'(?:''|[^'])*'")

logger = logging.getLogger("adf-convert")


# ---------------------------------------------------------------------------
# Helpers
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


def is_viewobject_xml(filepath: Path) -> bool:
    """Check if an XML file is likely an ADF ViewObject definition."""
    try:
        data = filepath.read_bytes()
        # Quick check: must contain <ViewObject (with bc4j namespace or not)
        if b"<ViewObject" not in data:
            return False
        # Must be an XML file
        return filepath.suffix.lower() == ".xml"
    except Exception:
        return False


# ---------------------------------------------------------------------------
# ResourceBundle loader (.properties files)
# ---------------------------------------------------------------------------
def load_bundle(bundle_classpath: str, project_root: Path) -> dict[str, str]:
    """
    Load a Java .properties resource bundle referenced by a dotted classpath
    like 'demo.model.Bundle'.

    Returns a dict of key -> value (unicode strings).
    """
    rel_path = bundle_classpath.replace(".", "/") + ".properties"
    result: dict[str, str] = {}
    for fp in _find_src_files(project_root, rel_path):
        result.update(_read_properties(fp))
    return result


def _read_properties(filepath: Path) -> dict[str, str]:
    """Read a .properties file and return key-value pairs."""
    try:
        text = filepath.read_text(encoding="utf-8-sig", errors="replace")
    except Exception:
        try:
            text = filepath.read_text(encoding="cp1251", errors="replace")
        except Exception:
            return {}

    result: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        result[key.strip()] = _unescape_java_unicode(value.strip())
    return result


def _unescape_java_unicode(text: str) -> str:
    """Convert \\uXXXX sequences to actual unicode characters."""
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), text)


# ---------------------------------------------------------------------------
# ViewObject XML parser
# ---------------------------------------------------------------------------
def parse_viewobject(xml_path: Path, project_root: Path) -> dict | None:
    """Parse one ADF ViewObject XML file and return extracted metadata.

    Returns None if the file is not a valid ViewObject or has no query.
    """
    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()
    except ET.ParseError as e:
        logger.warning("XML parse error in %s: %s", xml_path, e)
        return None

    # Check for bc4j namespace or local-name match
    tag = root.tag
    if tag == "ViewObject":
        ns = ""
    elif tag.endswith("}ViewObject"):
        ns = tag.rsplit("}", 1)[0].lstrip("{")
    else:
        return None

    # Helper for namespace-aware find
    def _find(elem, path: str) -> ET.Element | None:
        if ns:
            return elem.find(path.replace("{ns}", f"{{{ns}}}"))
        return elem.find(path)

    def _findall(elem, path: str) -> list[ET.Element]:
        if ns:
            return elem.findall(path.replace("{ns}", f"{{{ns}}}"))
        return elem.findall(path)

    vo_name = root.get("Name", "")
    if not vo_name:
        return None

    is_custom = root.get("CustomQuery", "false").lower() == "true"
    select_attr = root.get("SelectList", "")
    from_attr = root.get("FromList", "")
    where_attr = root.get("Where", "")
    order_attr = root.get("OrderBy", "")

    # --- SQL ---
    sql_text = ""
    if is_custom:
        sql_elem = _find(root, "{ns}SQLQuery")
        if sql_elem is not None and sql_elem.text:
            sql_text = sql_elem.text.strip()
    elif select_attr and from_attr:
        # Reconstruct SQL from entity-based attributes
        parts = ["SELECT", select_attr, "FROM", from_attr]
        if where_attr:
            parts.extend(["WHERE", where_attr])
        sql_text = " ".join(parts)

    if not sql_text:
        return None  # No usable SQL

    # --- Variables (bind parameters) ---
    variables: list[dict] = []
    for var_elem in _findall(root, "{ns}Variable"):
        name = var_elem.get("Name", "")
        kind = var_elem.get("Kind", "")
        var_type = var_elem.get("Type", "")
        if name and kind in ("where", "viewcriteria"):
            variables.append({
                "name": name,
                "kind": kind,
                "type": _simplify_java_type(var_type),
            })

    # --- View Attributes (output columns) ---
    view_attrs: list[dict] = []
    for attr_elem in _findall(root, "{ns}ViewAttribute"):
        attr_name = attr_elem.get("Name", "")
        alias = attr_elem.get("AliasName", "")
        expression = attr_elem.get("Expression", "")
        expr_to_use = expression or alias or attr_name

        # Get ResId from LABEL property
        res_id = ""
        props = _find(attr_elem, "{ns}Properties")
        if props is not None:
            sbp = _find(props, "{ns}SchemaBasedProperties")
            if sbp is not None:
                label_elem = _find(sbp, "{ns}LABEL")
                if label_elem is not None:
                    res_id = label_elem.get("ResId", "")

        view_attrs.append({
            "name": attr_name,
            "alias": alias,
            "expression": expr_to_use,
            "resId": res_id,
            "isSelected": attr_elem.get("IsSelected", "true").lower() != "false",
        })

    # --- ResourceBundle ---
    bundle_path = ""
    rb_elem = _find(root, "{ns}ResourceBundle")
    if rb_elem is not None:
        pb_elem = _find(rb_elem, "{ns}PropertiesBundle")
        if pb_elem is not None:
            bundle_path = pb_elem.get("PropertiesFile", "")

    # --- VO-level LABEL ResId ---
    vo_res_id = ""
    vo_props = _find(root, "{ns}Properties")
    if vo_props is not None:
        vo_sbp = _find(vo_props, "{ns}SchemaBasedProperties")
        if vo_sbp is not None:
            vo_label = _find(vo_sbp, "{ns}LABEL")
            if vo_label is not None:
                vo_res_id = vo_label.get("ResId", "")

    # --- Determine module ---
    module = _detect_module(xml_path, project_root)

    # --- Relative path ---
    try:
        rel_path = xml_path.relative_to(project_root).as_posix()
    except ValueError:
        rel_path = xml_path.name

    return {
        "name": vo_name,
        "sql": sql_text,
        "is_custom": is_custom,
        "variables": variables,
        "view_attrs": view_attrs,
        "bundle_path": bundle_path,
        "vo_res_id": vo_res_id,
        "module": module,
        "rel_path": rel_path,
        "order_by": order_attr,
    }


def _simplify_java_type(java_type: str) -> str:
    """Map ADF Java types to simple type names."""
    mapping = {
        "oracle.jbo.domain.Number": "numeric",
        "oracle.jbo.domain.Date": "date",
        "oracle.jbo.domain.Timestamp": "timestamp",
        "java.lang.String": "string",
        "java.lang.Boolean": "boolean",
        "java.lang.Integer": "integer",
        "java.lang.Long": "long",
        "java.math.BigDecimal": "numeric",
        "java.sql.Date": "date",
        "java.sql.Timestamp": "timestamp",
    }
    return mapping.get(java_type, java_type.rsplit(".", 1)[-1] if "." in java_type else java_type)


def _detect_module(xml_path: Path, project_root: Path) -> str:
    """Guess the module or component from the XML's location.

    Walks the relative path until it finds a `src/` segment; the directory
    immediately before `src/` becomes the module name.  If the XML is directly
    under the project root (no module wrapper), uses the first package segment
    after `src/`.
    """
    try:
        rel = xml_path.relative_to(project_root).as_posix()
    except ValueError:
        return "unknown"

    parts = rel.split("/")
    try:
        src_idx = parts.index("src")
    except ValueError:
        return "unknown"

    if src_idx >= 1:
        return parts[src_idx - 1]

    # Fallback: first package segment after src/
    if src_idx + 1 < len(parts):
        return parts[src_idx + 1]
    return "unknown"


def _find_src_dirs(project_root: Path) -> list[Path]:
    """Return all `src/` directories under the project root, walking the
    whole tree and excluding `classes/` or `lib/` paths.
    """
    src_dirs: list[Path] = []
    for candidate in project_root.rglob("src"):
        if not candidate.is_dir():
            continue
        posix = candidate.as_posix()
        if "/classes/" in posix or "/lib/" in posix:
            continue
        parent = candidate.parent.as_posix()
        if "/src/" in parent:
            continue
        src_dirs.append(candidate)
    return sorted(src_dirs)


def _find_src_files(project_root: Path, rel_path: str) -> list[Path]:
    """Find a file under any `src/` directory (excluding classes/, lib/)."""
    results: list[Path] = []
    for src_dir in project_root.rglob("src"):
        if not src_dir.is_dir():
            continue
        parent_str = src_dir.as_posix()
        if "/classes/" in parent_str or "/lib/" in parent_str:
            continue
        candidate = src_dir / rel_path
        if candidate.is_file():
            results.append(candidate)
    return results


# ---------------------------------------------------------------------------
# Build QueryUsageRecord from parsed ViewObject data
# ---------------------------------------------------------------------------
def vo_to_record(
    vo_info: dict,
    bundle: dict[str, str],
    project_root: Path,
) -> dict | None:
    """Convert parsed ViewObject metadata to a QueryUsageRecord dict."""
    sql = vo_info["sql"]
    if not sql.strip():
        return None

    vo_name = vo_info["name"]
    module = vo_info["module"]
    rel_path = vo_info["rel_path"]

    # --- Resolve business label from bundle ---
    business_label = vo_info["vo_res_id"]
    if business_label:
        resolved = bundle.get(business_label, "")
        if resolved:
            business_label = resolved
        else:
            business_label = vo_name
    else:
        business_label = vo_name

    # --- Parameters from bind variables ---
    sql_params = find_bind_parameters(sql)
    seen_param_names: set[str] = set()
    parameters: list[dict] = []

    # First add declared variables
    for var in vo_info["variables"]:
        name = var["name"]
        if name.lower() in seen_param_names:
            continue
        seen_param_names.add(name.lower())

        entry: dict = {"name": name}
        if var.get("type"):
            entry["dataType"] = var["type"]

        for possible_key in _possible_label_keys(rel_path, vo_name, name):
            if possible_key in bundle:
                entry["businessLabel"] = bundle[possible_key]
                break

        parameters.append(entry)

    # Add bind parameters not declared in <Variable>
    for bp in sql_params:
        if bp.lower() not in seen_param_names:
            seen_param_names.add(bp.lower())
            entry: dict = {"name": bp, "dataType": "string"}
            for possible_key in _possible_label_keys(rel_path, vo_name, bp):
                if possible_key in bundle:
                    entry["businessLabel"] = bundle[possible_key]
                    break
            parameters.append(entry)

    # --- Outputs ---
    outputs: list[dict] = []
    field_usages: list[dict] = []

    for attr in vo_info["view_attrs"]:
        # Skip non-selected attributes (transient/programmatic)
        if not attr["isSelected"]:
            continue

        alias = attr["alias"] or attr["name"]
        expression = attr["expression"]

        output_entry: dict = {"alias": alias}
        if expression and expression != alias:
            output_entry["sourceExpression"] = expression

        # Resolve business label
        label = ""
        if attr["resId"] and attr["resId"] in bundle:
            label = bundle[attr["resId"]]
        else:
            for possible_key in _possible_label_keys(rel_path, vo_name, attr["name"]):
                if possible_key in bundle:
                    label = bundle[possible_key]
                    break

        if label:
            output_entry["businessLabel"] = label

        outputs.append(output_entry)

        # --- fieldUsage for each output ---
        fu_entry: dict = {
            "output": alias,
            "transformation": {"kind": "identity"},
            "location": {"kind": "view-attribute"},
            "confidence": "high",
        }
        if label:
            fu_entry["businessObject"] = label
        field_usages.append(fu_entry)

    # --- Business tags ---
    tags = sorted({
        "oracle-adf",
        module,
        "custom-query" if vo_info["is_custom"] else "entity-based",
    })

    # --- Build record ---
    record: dict = {
        "schemaVersion": SCHEMA_VERSION,
        "source": {
            "kind": "oracle-adf-view",
            "path": rel_path,
            "unit": vo_name,
        },
        "businessLabel": business_label,
        "businessDomain": module,
        "businessTags": tags,
        "sql": sql.strip(),
        "parameters": parameters,
        "outputs": outputs,
        "fieldUsages": field_usages,
        "sourceMeta": {
            "adapter": "oracle-adf",
            "adapterVersion": "1.0.0",
            "viewObjectName": vo_name,
            "module": module,
            "isCustomQuery": vo_info["is_custom"],
        },
    }

    return record


def _possible_label_keys(rel_path: str, vo_name: str, attr_or_param_name: str) -> list[str]:
    """Generate possible ResId key variants found in bundle properties.

    Java bundle key format:
      <dotted_path_including_filename>.<AttributeName>_LABEL
      <dotted_path_including_filename>.<AttributeName>_TOOLTIP

    Since dotted already includes the XML filename (which equals the VO name
    in most projects), the PRIMARY key does NOT repeat vo_name.  The ALT key
    adds vo_name for the case where the filename differs from the VO Name.
    """
    parts = rel_path.replace("\\", "/").split("/")
    try:
        src_idx = parts.index("src")
        dotted = ".".join(parts[src_idx + 1:])
    except ValueError:
        dotted = rel_path

    if dotted.endswith(".xml"):
        dotted = dotted[:-4]

    suffix_variants = ["_LABEL", "_TOOLTIP"]
    keys = []
    for suffix in suffix_variants:
        # PRIMARY: dotted already includes the VO filename, no repetition
        keys.append(f"{dotted}.{attr_or_param_name}{suffix}")
        # ALT: for when the XML filename differs from the VO Name attribute
        alt = f"{dotted}.{vo_name}.{attr_or_param_name}{suffix}"
        if alt not in keys:
            keys.append(alt)
    return keys


# ---------------------------------------------------------------------------
# Scanning
# ---------------------------------------------------------------------------
def find_viewobject_files(project_root: Path) -> list[Path]:
    """Find all ViewObject XML files under any `src/` directory."""
    results: list[Path] = []
    for src_dir in _find_src_dirs(project_root):
        for dirpath, _dirnames, filenames in os.walk(src_dir):
            for fn in filenames:
                if not fn.endswith(".xml"):
                    continue
                fp = Path(dirpath) / fn
                try:
                    if b"<ViewObject" in fp.read_bytes()[:4096]:
                        results.append(fp)
                except Exception:
                    continue
    return sorted(results)


def scan_and_convert(project_root: Path) -> list[dict]:
    """Scan project and convert all ViewObjects to QueryUsageRecords."""
    # Pre-load ALL bundle .properties files across the project
    bundle_cache: dict[str, dict[str, str]] = _preload_bundles(project_root)

    vo_files = find_viewobject_files(project_root)
    all_records: list[dict] = []
    logger.info("Found %d ViewObject XML files in %s", len(vo_files), project_root)

    for vo_file in vo_files:
        logger.debug("Processing %s ...", vo_file)
        vo_info = parse_viewobject(vo_file, project_root)
        if vo_info is None:
            continue

        # Load the specific bundle for this VO
        bundle: dict[str, str] = {}
        bundle_path = vo_info.get("bundle_path", "")
        if bundle_path:
            bundle = bundle_cache.get(bundle_path, {})
        else:
            # Try auto-detect: find the module bundle
            module = vo_info["module"]
            for bp, data in bundle_cache.items():
                if module in bp:
                    bundle = data
                    break

        record = vo_to_record(vo_info, bundle, project_root)
        if record is None:
            logger.debug("  Skipped (no SQL): %s", vo_info["name"])
            continue

        all_records.append(record)
        logger.debug("  -> %s (%d params, %d outputs)",
                     vo_info["name"],
                     len(record["parameters"]),
                     len(record["outputs"]))

    return all_records


def _preload_bundles(project_root: Path) -> dict[str, dict[str, str]]:
    """Pre-load all ResourceBundle .properties files found in the project."""
    cache: dict[str, dict[str, str]] = {}
    for filepath in project_root.rglob("*.properties"):
        posix = filepath.as_posix()
        if "/classes/" in posix or "/lib/" in posix:
            continue
        rel = _src_relative(filepath, project_root)
        if rel is None:
            continue
        dotted_path = rel.replace("/", ".")
        if dotted_path.endswith(".properties"):
            dotted_path = dotted_path[:-11]
        data = _read_properties(filepath)
        if data:
            cache[dotted_path] = data
            logger.debug("Loaded bundle: %s (%d keys)", dotted_path, len(data))
    logger.info("Loaded %d resource bundles", len(cache))
    return cache


def _src_relative(filepath: Path, project_root: Path) -> str | None:
    """Return the path segment after the first `src/` directory, or None."""
    try:
        rel = filepath.relative_to(project_root).as_posix()
    except ValueError:
        return None
    try:
        src_idx = rel.index("/src/")
        return rel[src_idx + 5:]
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(
        description="Convert Oracle ADF ViewObject sources to jdbc-mcp-server QueryUsageRecord format.",
    )
    parser.add_argument("path", nargs="?", default=".",
                        help="Path to ADF project root (default: current directory)")
    parser.add_argument("--output", "-o", default="oracle-adf.json",
                        help="Output JSON file")
    parser.add_argument("--pretty", "-p", action="store_true", default=True,
                        help="Pretty-print output (default: true)")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Verbose logging")
    args = parser.parse_args()

    level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(level=level, format="%(levelname)s: %(message)s")

    project_root = Path(args.path).resolve()
    if not project_root.is_dir():
        logger.error("Not a directory: %s", project_root)
        return 1

    records = scan_and_convert(project_root)
    indent = 2 if args.pretty else None
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=indent)

    logger.info("Written %d records to %s", len(records), args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())