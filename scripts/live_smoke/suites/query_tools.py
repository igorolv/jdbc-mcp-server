from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from scripts.live_smoke.mcp_client import McpClient, McpClientError
from scripts.live_smoke.profiles import DbProfile


@dataclass(frozen=True)
class CheckResult:
    name: str
    status: str
    detail: str = ""


def run(client: McpClient, profile: DbProfile, schema: str | None) -> list[CheckResult]:
    results: list[CheckResult] = []

    def check(name: str, func: Any) -> None:
        try:
            detail = func() or ""
            results.append(CheckResult(name, "PASS", detail))
        except SkipCheck as exc:
            results.append(CheckResult(name, "SKIP", str(exc)))
        except Exception as exc:
            results.append(CheckResult(name, "FAIL", str(exc)))

    check("tools/list contains core query tools", lambda: _check_tools(client))
    check("listSchemas", lambda: _list_schemas(client))
    check("inspectQuery ping", lambda: _inspect_ping(client, profile))
    check("validateQuery ping", lambda: _validate_ping(client, profile))
    check("executeQuery ping", lambda: _execute_ping(client, profile))
    check("resolveQueryLineage ping", lambda: _lineage_ping(client, profile, schema))
    check("resolveQueryLineage first visible view", lambda: _lineage_first_view(client, profile, schema))
    return results


class SkipCheck(RuntimeError):
    pass


def _check_tools(client: McpClient) -> str:
    payload = client.list_tools()
    tools = payload.get("result", {}).get("tools", [])
    names = {tool.get("name") for tool in tools}
    required = {"inspectQuery", "validateQuery", "executeQuery", "resolveQueryLineage"}
    missing = sorted(required - names)
    if missing:
        raise AssertionError("missing tools: " + ", ".join(missing))
    return f"{len(tools)} tools advertised"


def _list_schemas(client: McpClient) -> str:
    data = client.tool_json(client.call_tool("listSchemas", {"includeSystem": False}))
    if not isinstance(data, list):
        raise AssertionError("listSchemas did not return a JSON array")
    return f"{len(data)} schemas"


def _inspect_ping(client: McpClient, profile: DbProfile) -> str:
    data = client.tool_json(client.call_tool("inspectQuery", {"sql": profile.ping_sql}))
    if not data.get("parseable"):
        raise AssertionError("inspectQuery did not parse ping SQL")
    return data.get("statementType", "")


def _validate_ping(client: McpClient, profile: DbProfile) -> str:
    data = client.tool_json(client.call_tool("validateQuery", {"sql": profile.ping_sql}))
    if data.get("valid") is not True:
        raise AssertionError("validateQuery returned invalid: " + json.dumps(data, ensure_ascii=False))
    return f"columns={data.get('columns')}"


def _execute_ping(client: McpClient, profile: DbProfile) -> str:
    data = client.tool_json(client.call_tool("executeQuery", {"sql": profile.ping_sql, "limit": 1}))
    rows = data.get("rows") or []
    if not rows:
        raise AssertionError("executeQuery returned no rows for ping SQL")
    return f"rowCount={data.get('rowCount')}"


def _lineage_ping(client: McpClient, profile: DbProfile, schema: str | None) -> str:
    data = client.tool_json(
        client.call_tool(
            "resolveQueryLineage",
            {"sql": profile.ping_sql, "schema": schema, "maxDepth": 3},
        )
    )
    if "directObjects" not in data or "expandedPhysicalTables" not in data:
        raise AssertionError("lineage response is missing expected fields")
    return f"direct={len(data.get('directObjects') or [])}"


def _lineage_first_view(client: McpClient, profile: DbProfile, schema: str | None) -> str:
    view_query = profile.first_view_sql(schema)
    view_rows = client.tool_json(client.call_tool("executeQuery", {"sql": view_query, "limit": 1})).get("rows") or []
    if not view_rows:
        raise SkipCheck("no views visible in selected schema")
    row = view_rows[0]
    view_schema = _get_ci(row, profile.view_schema_column) or schema
    view_name = _get_ci(row, profile.view_name_column)
    if not view_name:
        raise AssertionError("view discovery query did not return a view name")

    sql = "SELECT * FROM " + profile.qualified_name(view_schema, view_name)
    data = client.tool_json(
        client.call_tool(
            "resolveQueryLineage",
            {
                "sql": sql,
                "schema": schema,
                "expandViews": True,
                "expandRoutines": True,
                "maxDepth": 5,
            },
        )
    )
    direct = data.get("directObjects") or []
    expanded = data.get("expandedPhysicalTables") or []
    if not direct:
        raise AssertionError("view lineage returned no direct objects")
    return f"{view_schema}.{view_name}: physicalTables={len(expanded)}"


def _get_ci(row: dict[str, Any], key: str) -> Any:
    if key in row:
        return row[key]
    for k, v in row.items():
        if k.lower() == key.lower():
            return v
    return None
