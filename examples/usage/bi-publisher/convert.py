#!/usr/bin/env python3
"""
Convert Oracle BI Publisher report sources (XDO/XDM directories)
to jdbc-mcp-server QueryUsageRecord format.

Usage:
  python convert.py <path-to-bi-publisher-sources> [--output records.json]

The script scans a directory tree for .xdo directories containing _report.xdo
and their matching .xdm directories with _datamodel.xdm. For each SQL dataset
found it emits one QueryUsageRecord compatible with
query-usage-record.schema.json.
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

try:
    import xlrd
except ImportError:
    xlrd = None

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
SCHEMA_VERSION = 1
NS = {"ns": "http://xmlns.oracle.com/oxp/xmlp"}
BIND_PARAMETER_RE = re.compile(r"(?<!:):([A-Za-z_][A-Za-z0-9_$#]*)")
SQL_STRING_RE = re.compile(r"'(?:''|[^'])*'")

logger = logging.getLogger("bip-convert")


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------
def find_xdo_directories(root: Path) -> list[Path]:
    result = []
    for dirpath, dirnames, _ in os.walk(root):
        path = Path(dirpath)
        if path.suffix == ".xdo" and (path / "_report.xdo").is_file():
            result.append(path)
    return sorted(result)


def get_xdm_directory(xdo_dir: Path) -> Path | None:
    parent = xdo_dir.parent
    name = xdo_dir.name
    if name.endswith(".xdo"):
        candidate = parent / (name[:-4] + ".xdm")
        if candidate.is_dir() and (candidate / "_datamodel.xdm").is_file():
            return candidate
    return None


def get_report_name(xdo_dir: Path) -> str:
    return xdo_dir.name[:-4] if xdo_dir.name.endswith(".xdo") else xdo_dir.name


def relative_path(root: Path, target: Path) -> str:
    return target.relative_to(root).as_posix()


def strip_sql_string_literals(sql_text: str) -> str:
    return SQL_STRING_RE.sub(lambda m: " " * len(m.group(0)), sql_text)


def find_bind_parameters(sql_text: str) -> list[str]:
    parameters = []
    seen = set()
    cleaned = strip_sql_string_literals(sql_text)
    for match in BIND_PARAMETER_RE.finditer(cleaned):
        name = match.group(1)
        key = name.lower()
        if key not in seen:
            seen.add(key)
            parameters.append(name)
    return parameters


# ---------------------------------------------------------------------------
# XDO parser  (_report.xdo)
# ---------------------------------------------------------------------------
def parse_xdo(xdo_dir: Path) -> dict:
    report_file = xdo_dir / "_report.xdo"
    try:
        tree = ET.parse(report_file)
        root = tree.getroot()
    except ET.ParseError as e:
        logger.warning("XML parse error in %s: %s", report_file, e)
        return {"description": "", "templates": []}

    result: dict = {"description": "", "templates": []}

    desc = root.find("ns:description", NS)
    if desc is not None and desc.text:
        result["description"] = desc.text.strip()

    templates = root.find("ns:templates", NS)
    if templates is not None:
        default_label = templates.get("default", "")
        for tmpl in templates.findall("ns:template", NS):
            entry = {
                "label": tmpl.get("label", ""),
                "url": tmpl.get("url", ""),
                "type": tmpl.get("type", ""),
                "outputFormat": tmpl.get("outputFormat", ""),
                "defaultFormat": tmpl.get("defaultFormat", ""),
                "locale": tmpl.get("locale", ""),
                "active": tmpl.get("active", "false") == "true",
                "isDefault": tmpl.get("label", "") == default_label,
            }
            result["templates"].append(entry)

    if not result["description"] and result["templates"]:
        default = next((t for t in result["templates"] if t["isDefault"]), result["templates"][0])
        result["description"] = default["label"]

    return result


# ---------------------------------------------------------------------------
# XDM parser  (_datamodel.xdm)
# ---------------------------------------------------------------------------
def parse_data_structure_group(group_elem) -> dict:
    result = {
        "name": group_elem.get("name", ""),
        "label": group_elem.get("label", ""),
        "source": group_elem.get("source", ""),
        "elements": [],
        "groups": [],
    }
    for elem in group_elem.findall("ns:element", NS):
        result["elements"].append({
            "name": elem.get("name", ""),
            "value": elem.get("value", ""),
            "label": elem.get("label", ""),
            "dataType": elem.get("dataType", ""),
            "fieldOrder": elem.get("fieldOrder", ""),
            "breakOrder": elem.get("breakOrder", ""),
        })
    for child in group_elem.findall("ns:group", NS):
        result["groups"].append(parse_data_structure_group(child))
    return result


def parse_xdm(xdm_dir: Path) -> dict:
    model_file = xdm_dir / "_datamodel.xdm"
    try:
        tree = ET.parse(model_file)
        root = tree.getroot()
    except ET.ParseError as e:
        logger.warning("XML parse error in %s: %s", model_file, e)
        return {"parameters": [], "datasets": [], "data_structure": [], "data_source": ""}

    result: dict = {
        "parameters": [],
        "datasets": [],
        "data_structure": [],
        "data_source": root.get("defaultDataSourceRef", ""),
    }

    params = root.find("ns:parameters", NS)
    if params is not None:
        for p in params.findall("ns:parameter", NS):
            inp = p.find("ns:input", NS)
            result["parameters"].append({
                "name": p.get("name", ""),
                "dataType": p.get("dataType", ""),
                "defaultValue": p.get("defaultValue", ""),
                "label": inp.get("label", "") if inp is not None else "",
            })

    datasets = root.find("ns:dataSets", NS)
    if datasets is not None:
        for ds in datasets.findall("ns:dataSet", NS):
            sql_elem = ds.find("ns:sql", NS)
            sql_text = sql_elem.text.strip() if sql_elem is not None and sql_elem.text else ""
            result["datasets"].append({
                "name": ds.get("name", ""),
                "type": ds.get("type", ""),
                "sql": sql_text,
                "parameters_used": find_bind_parameters(sql_text),
            })

    ds_elem = root.find(".//ns:dataStructure", NS)
    if ds_elem is not None:
        for group in ds_elem.findall("ns:group", NS):
            result["data_structure"].append(parse_data_structure_group(group))

    return result


# ---------------------------------------------------------------------------
# Template parsers (simplified versions matching bi-publisher approach)
# ---------------------------------------------------------------------------

# --- RTF ---
EXPRESSION_RE = re.compile(r"<\?.*?\?>", re.DOTALL)
FIELD_IN_EXPR_RE = re.compile(r"<\?\s*([A-Za-z_][A-Za-z0-9_$#]*)\s*\?>")


def parse_rtf_template(path: Path) -> list[dict]:
    """Extract BI Publisher field references from an RTF template."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except Exception:
        try:
            text = path.read_text(encoding="cp1251", errors="replace")
        except Exception as e:
            logger.warning("Cannot read RTF %s: %s", path, e)
            return []

    usages = []
    seen = set()

    for expr_match in EXPRESSION_RE.finditer(text):
        expr = expr_match.group(0)
        for field_match in FIELD_IN_EXPR_RE.finditer(expr):
            field = field_match.group(1)
            if field.lower() in {"if", "for-each", "end", "choose", "when", "otherwise",
                                 "for", "xdofx", "xdoxslt", "xdoutils", "xdoctx"}:
                continue
            key = field.lower()
            if key not in seen:
                seen.add(key)
                usages.append({
                    "field": field,
                    "expression": expr,
                    "usage_type": "expression",
                    "source": "rtf",
                    "confidence": "medium",
                })
            elif key in seen:
                usages.append({
                    "field": field,
                    "expression": "",
                    "usage_type": "field",
                    "source": "rtf",
                    "confidence": "medium",
                })

    return usages


# --- XLS ---
XDO_BINDING_RE = re.compile(r"^XDO_\?(.+)\?$")
PI_FIELD_RE = re.compile(r"<\?\s*([A-Za-z_][A-Za-z0-9_$#]*)\s*\?>")


def parse_xls_template(path: Path, headers_context: dict | None = None) -> list[dict]:
    """Extract field usages from an XLS template via XDO_* named ranges."""
    if xlrd is None:
        logger.warning("xlrd not installed, skipping XLS: %s", path)
        return []

    usages = []
    try:
        wb = xlrd.open_workbook(str(path), formatting_info=True)
    except Exception as e:
        logger.warning("Cannot read XLS %s: %s", path, e)
        return []

    field_info = _gather_xdo_bindings(wb)
    for field_name, info_list in field_info.items():
        for info in info_list:
            headers = _find_visible_headers(wb, info["sheet"], info["col"], info.get("row", 0))
            usage_type = "field"
            if info.get("expression"):
                if re.search(r"\bsum\b", info["expression"], re.IGNORECASE):
                    usage_type = "aggregate"
                else:
                    usage_type = "derived"
            usages.append({
                "field": field_name,
                "binding_name": info.get("binding_name", ""),
                "expression": info.get("expression", ""),
                "usage_type": usage_type,
                "sheet": info["sheet"],
                "cell": info.get("cell", ""),
                "headers": headers,
                "confidence": "high" if headers else "medium",
            })

    return usages


def _gather_xdo_bindings(wb: xlrd.Book) -> dict[str, list]:
    """Find XDO_?field? named ranges and normalise field names."""
    field_map: dict[str, list] = {}

    for name_def in wb.name_map.values():
        for name_item in name_def:
            raw_name = name_item.name
            match = XDO_BINDING_RE.match(raw_name)
            if not match:
                continue
            raw_field = match.group(1)
            # strip numeric suffix like _2, _3 etc.
            field = re.sub(r"_\d+$", "", raw_field)

            # Resolve sheet and cell from the name formula (binary-encoded)
            sheet_name = _resolve_sheet_name(wb, name_item)
            cell_info = _get_cell_from_area(name_item)
            cell_addr = cell_info[0] if cell_info else raw_name
            row = cell_info[1] if cell_info else 0
            col = cell_info[2] if cell_info else 0
            expression = _get_expression_from_raw_formula(name_item)

            field_map.setdefault(field, []).append({
                "field": raw_field,
                "binding_name": raw_name,
                "expression": expression,
                "sheet": sheet_name or "Unknown",
                "cell": cell_addr,
                "row": row,
                "col": col,
            })

    return field_map


def _resolve_sheet_name(wb: xlrd.Book, name_item) -> str | None:
    try:
        a2d = name_item.area2d()
        if a2d and len(a2d) >= 5:
            sheet_info = a2d[0]
            if isinstance(sheet_info, str):
                return sheet_info
            if isinstance(sheet_info, xlrd.sheet.Sheet):
                return sheet_info.name
            if isinstance(sheet_info, int) and 0 <= sheet_info < wb.nsheets:
                return wb.sheet_by_index(sheet_info).name
        if name_item.excel_sheet_index >= 0:
            idx = name_item.excel_sheet_index
            if 0 <= idx < wb.nsheets:
                return wb.sheet_by_index(idx).name
    except Exception:
        pass
    return None


def _get_cell_from_area(name_item) -> tuple[str, int, int] | None:
    try:
        a2d = name_item.area2d()
        if a2d and len(a2d) >= 5:
            rowx1, colx1 = a2d[1], a2d[3]
            return xlrd.cellname(rowx1, colx1), rowx1, colx1
    except Exception:
        pass
    return None


def _get_cell_from_area(name_item) -> tuple[str, int, int] | None:
    try:
        a2d = name_item.area2d()
        if a2d and len(a2d) >= 5:
            rowx1, colx1 = a2d[1], a2d[3]
            return xlrd.cellname(rowx1, colx1), rowx1, colx1
    except Exception:
        pass
    return None


def _get_expression_from_raw_formula(name_item) -> str:
    """Try to extract a BI Publisher expression from raw_formula bytes."""
    try:
        raw = name_item.raw_formula
        text = raw.decode("utf-8", errors="replace") if isinstance(raw, bytes) else str(raw)
        expressions = PI_FIELD_RE.findall(text)
        return "; ".join(expressions) if expressions else ""
    except Exception:
        return ""


def _find_visible_headers(wb: xlrd.Book, sheet_name: str, col: int, row: int) -> list[str]:
    headers = []
    if not sheet_name or sheet_name == "Unknown":
        return headers
    try:
        sheet = wb.sheet_by_name(sheet_name)
    except KeyError:
        return headers
    except KeyError:
        return headers
    for r in range(max(0, row - 10), row):
        if r >= sheet.nrows:
            break
        val = sheet.cell_value(r, col)
        text = str(val).strip() if val else ""
        if text and not text.replace(".", "").replace(",", "").isdigit():
            headers.append(text)
    return headers


# ---------------------------------------------------------------------------
# Core conversion: one report → list[QueryUsageRecord]
# ---------------------------------------------------------------------------
def report_to_records(
    xdo_dir: Path,
    xdo_info: dict,
    xdm_info: dict,
    source_root: Path,
) -> list[dict]:
    base_source_path = relative_path(source_root, xdo_dir)
    module = xdo_dir.relative_to(source_root).parts[0] if xdo_dir != source_root else xdo_dir.parent.name
    report_name = get_report_name(xdo_dir)
    description = xdo_info.get("description", report_name)

    # Pre-parse template files for field usages
    templates = xdo_info.get("templates", [])
    template_field_usages = _parse_template_files(xdo_dir, templates)

    records = []
    for ds in xdm_info.get("datasets", []):
        sql = ds.get("sql", "").strip()
        if not sql:
            continue

        ds_name = ds.get("name", "UNKNOWN")

        # Map template field usages relevant to this dataset
        f_usages = _map_field_usages(template_field_usages, ds_name, xdm_info.get("data_structure", []))

        record = {
            "schemaVersion": SCHEMA_VERSION,
            "source": {
                "kind": "bi-publisher-report",
                "path": base_source_path,
                "unit": ds_name,
            },
            "businessLabel": f"{description} / {ds_name}",
            "businessDomain": module,
            "businessTags": sorted({
                "bi-publisher",
                module,
                ds.get("type", "sql"),
            }),
            "sql": sql,
            "parameters": _build_parameters(xdm_info.get("parameters", []), ds.get("parameters_used", [])),
            "outputs": _build_outputs(xdm_info.get("data_structure", []), ds_name),
            "fieldUsages": f_usages,
            "sourceMeta": {
                "adapter": "bi-publisher",
                "adapterVersion": "1.0.0",
                "report": report_name,
                "dataset": ds_name,
                "module": module,
                "templates": [
                    {"label": t.get("label", ""), "type": t.get("type", ""), "url": t.get("url", "")}
                    for t in templates
                ],
            },
        }

        if not record["outputs"]:
            del record["outputs"]

        records.append(record)

    return records


def _parse_template_files(xdo_dir: Path, templates: list[dict]) -> list[dict]:
    """Parse RTF/XLS template files inside the .xdo directory."""
    all_usages: list[dict] = []
    for tmpl in templates:
        url = tmpl.get("url", "")
        if not url:
            continue
        tmpl_type = tmpl.get("type", "")

        # Try exact match first, then locale-prefixed variants
        candidates = list(xdo_dir.glob(url)) + list(xdo_dir.glob(f"*{Path(url).stem}*{Path(url).suffix}"))
        seen_paths = set()
        for tp in candidates:
            if tp in seen_paths or not tp.is_file():
                continue
            seen_paths.add(tp)
            ext = tp.suffix.lower()
            usages = []
            if tmpl_type == "rtf" and ext == ".rtf":
                usages = parse_rtf_template(tp)
            elif tmpl_type == "excel" and ext == ".xls":
                usages = parse_xls_template(tp)
            if usages:
                for u in usages:
                    u["template_label"] = tmpl.get("label", "")
                    u["template_url"] = url
                all_usages.extend(usages)

    return all_usages


def _map_field_usages(
    template_usages: list[dict],
    dataset_name: str,
    data_structure: list[dict],
) -> list[dict]:
    """Map template field usages to QueryUsageRecord format.
    
    Attempts to correlate template fields with data-structure elements
    belonging to the given dataset (via group.source == dataset name).
    """
    # Build set of field names belonging to this dataset's data structure
    ds_fields = _collect_ds_fields(data_structure, dataset_name)

    result = []
    for fu in template_usages:
        field = fu.get("field", "")
        usage_type = fu.get("usage_type", "field")

        # Determine transformation kind
        kind_map = {
            "field": "identity",
            "aggregate": "aggregate",
            "derived": "derived",
            "expression": "derived",
        }
        tk = kind_map.get(usage_type, "other")

        # Business object = report description or template label
        business_object = fu.get("template_label", "")

        # Location kind
        source = fu.get("source", "")
        if source == "rtf":
            location_kind = "template-field"
        else:
            location_kind = "excel-cell"

        location_details = {}
        if fu.get("sheet"):
            location_details["sheet"] = fu["sheet"]
        if fu.get("cell"):
            location_details["cell"] = fu["cell"]
        if fu.get("binding_name"):
            location_details["bindingName"] = fu["binding_name"]

        entry = {
            "output": field if field in ds_fields else None,
            "businessObject": business_object or None,
            "transformation": {
                "kind": tk,
                "description": fu.get("expression", "") or None,
            },
            "location": {
                "kind": location_kind,
                "details": location_details if location_details else None,
            },
            "headers": fu.get("headers", []),
            "confidence": fu.get("confidence", "medium"),
        }

        if entry["output"] is None:
            del entry["output"]
        if entry["businessObject"] is None:
            del entry["businessObject"]
        if entry["transformation"]["description"] is None:
            del entry["transformation"]["description"]
        if entry["location"]["details"] is None:
            del entry["location"]["details"]
        if not entry["headers"]:
            del entry["headers"]

        result.append(entry)

    return result


def _collect_ds_fields(data_structure: list[dict], dataset_name: str) -> set[str]:
    """Collect element names from data structure groups whose source references dataset_name."""
    fields: set[str] = set()
    for group in data_structure:
        if group.get("source", "").lower() == dataset_name.lower() or dataset_name == "UNKNOWN":
            for elem in group.get("elements", []):
                if elem.get("name"):
                    fields.add(elem["name"])
            # Recurse into subgroups
            _collect_group_fields(group, fields)
    return fields


def _collect_group_fields(group: dict, acc: set[str]) -> None:
    for elem in group.get("elements", []):
        if elem.get("name"):
            acc.add(elem["name"])
    for child in group.get("groups", []):
        _collect_group_fields(child, acc)


def _build_parameters(
    report_params: list[dict],
    bind_params: list[str],
) -> list[dict]:
    seen_names: set[str] = set()
    result: list[dict] = []

    for rp in report_params:
        name = rp.get("name", "")
        if not name or name.lower() in seen_names:
            continue
        seen_names.add(name.lower())
        entry = {
            "name": name,
            "dataType": rp.get("dataType", "xsd:string"),
        }
        if rp.get("defaultValue"):
            entry["defaultValue"] = rp["defaultValue"]
        if rp.get("label"):
            entry["businessLabel"] = rp["label"]
        result.append(entry)

    for bp in bind_params:
        if bp.lower() not in seen_names:
            seen_names.add(bp.lower())
            result.append({"name": bp, "dataType": "xsd:string"})

    return result


def _build_outputs(data_structure: list[dict], dataset_name: str) -> list[dict]:
    outputs: list[dict] = []
    for group in data_structure:
        if group.get("source", "").lower() == dataset_name.lower() or dataset_name == "UNKNOWN":
            _collect_outputs_from_group(group, outputs)
    return outputs


def _collect_outputs_from_group(group: dict, acc: list[dict]) -> None:
    for elem in group.get("elements", []):
        alias = elem.get("name", "")
        if not alias:
            continue
        entry: dict = {"alias": alias}
        if elem.get("value"):
            entry["sourceExpression"] = elem["value"]
        if elem.get("label"):
            entry["businessLabel"] = elem["label"]
        if elem.get("dataType"):
            entry["businessDescription"] = elem["dataType"]
        acc.append(entry)
    for child in group.get("groups", []):
        _collect_outputs_from_group(child, acc)


# ---------------------------------------------------------------------------
# Scan & convert entry point
# ---------------------------------------------------------------------------
def scan_and_convert(source_root: Path) -> list[dict]:
    xdo_dirs = find_xdo_directories(source_root)
    all_records: list[dict] = []
    logger.info("Found %d .xdo directories in %s", len(xdo_dirs), source_root)

    for xdo_dir in xdo_dirs:
        logger.info("Processing %s ...", relative_path(source_root, xdo_dir))
        xdm_dir = get_xdm_directory(xdo_dir)
        xdo_info = parse_xdo(xdo_dir)
        xdm_info = parse_xdm(xdm_dir) if xdm_dir else {"parameters": [], "datasets": [], "data_structure": [], "data_source": ""}

        if not xdm_info.get("datasets"):
            logger.info("  No SQL datasets found, skipping")
            continue

        records = report_to_records(xdo_dir, xdo_info, xdm_info, source_root)
        all_records.extend(records)
        logger.info("  → %d record(s)", len(records))

    return all_records


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(
        description="Convert BI Publisher sources to jdbc-mcp-server QueryUsageRecord format.",
    )
    parser.add_argument("path", nargs="?", default=".", help="Path to BI Publisher source tree (default: current directory)")
    parser.add_argument("--output", "-o", default="query_usage_records.json", help="Output JSON file")
    parser.add_argument("--pretty", "-p", action="store_true", default=True, help="Pretty-print output (default: true)")
    args = parser.parse_args()

    source_root = Path(args.path).resolve()
    if not source_root.is_dir():
        logger.error("Not a directory: %s", source_root)
        return 1

    records = scan_and_convert(source_root)
    indent = 2 if args.pretty else None
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=indent)

    logger.info("Written %d records to %s", len(records), args.output)
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    raise SystemExit(main())