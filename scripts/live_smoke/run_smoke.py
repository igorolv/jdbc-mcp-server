from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.live_smoke.mcp_client import McpClient, McpClientError, server_env
from scripts.live_smoke.profiles import PROFILES
from scripts.live_smoke.suites import run_query_tools


def main() -> int:
    args = parse_args()
    jar = Path(args.jar)
    if not jar.is_absolute():
        jar = REPO_ROOT / jar

    profile = PROFILES[args.db]
    schema = args.schema or profile.default_schema(os.environ)
    try:
        env = server_env({}, profile.env_prefix, schema)
        with McpClient(jar, env, REPO_ROOT, args.startup_timeout, args.verbose) as client:
            init = client.initialize()
            server = init.get("result", {}).get("serverInfo", {})
            print(f"server: {server.get('name')} {server.get('version')}")

            results = run_query_tools(client, profile, schema)
            failures = 0
            for result in results:
                print(f"{result.status:4} {result.name} {result.detail}".rstrip())
                if result.status == "FAIL":
                    failures += 1
            if failures:
                print(f"\n{failures} check(s) failed")
                return 1
            return 0
    except McpClientError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run live MCP smoke checks against jdbc-mcp-server.jar")
    parser.add_argument("--db", required=True, choices=sorted(PROFILES), help="Database profile")
    parser.add_argument("--jar", default="build/libs/jdbc-mcp-server.jar", help="Path to boot jar")
    parser.add_argument("--schema", help="Default schema override")
    parser.add_argument("--startup-timeout", type=float, default=45.0, help="Seconds to wait for Spring Boot startup")
    parser.add_argument("--verbose", action="store_true", help="Print server stderr and MCP response snippets")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(main())
