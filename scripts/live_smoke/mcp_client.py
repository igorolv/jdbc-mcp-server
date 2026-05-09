from __future__ import annotations

import json
import os
import queue
import subprocess
import threading
from pathlib import Path
from typing import Any


class McpClientError(RuntimeError):
    pass


class McpClient:
    """Small newline-delimited JSON MCP client for Spring AI stdio servers."""

    def __init__(
        self,
        jar: Path,
        env: dict[str, str],
        cwd: Path,
        startup_timeout: float = 45.0,
        verbose: bool = False,
    ) -> None:
        self.jar = jar
        self.env = env
        self.cwd = cwd
        self.startup_timeout = startup_timeout
        self.verbose = verbose
        self._next_id = 1
        self._responses: "queue.Queue[dict[str, Any]]" = queue.Queue()
        self._stderr_lines: list[str] = []
        self._started = threading.Event()
        self._process: subprocess.Popen[str] | None = None

    @property
    def stderr_tail(self) -> list[str]:
        return self._stderr_lines[-20:]

    def start(self) -> None:
        if not self.jar.exists():
            raise McpClientError(f"jar not found: {self.jar}")

        self._process = subprocess.Popen(
            ["java", "-jar", str(self.jar)],
            cwd=self.cwd,
            env=self.env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()
        if not self._started.wait(self.startup_timeout):
            code = self._process.poll()
            self.close()
            tail = "\n".join(self.stderr_tail)
            raise McpClientError(f"server startup timeout or exit, code={code}\n{tail}")

    def initialize(self) -> dict[str, Any]:
        response = self.request(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "jdbc-mcp-live-smoke", "version": "1"},
            },
        )
        self.notify("notifications/initialized", {})
        return response

    def list_tools(self) -> dict[str, Any]:
        return self.request("tools/list", {})

    def call_tool(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        return self.request(
            "tools/call",
            {"name": name, "arguments": arguments or {}},
        )

    def tool_text(self, response: dict[str, Any]) -> str:
        if response.get("error"):
            raise McpClientError(json.dumps(response["error"], ensure_ascii=False))
        result = response.get("result") or {}
        if result.get("isError"):
            content = result.get("content") or []
            text = content[0].get("text") if content else None
            raise McpClientError(text or "tool returned isError=true")
        content = result.get("content") or []
        if not content:
            return ""
        return str(content[0].get("text") or "")

    def tool_json(self, response: dict[str, Any]) -> Any:
        text = self.tool_text(response)
        if not text:
            return None
        return json.loads(text)

    def request(self, method: str, params: dict[str, Any], timeout: float = 30.0) -> dict[str, Any]:
        request_id = self._next_id
        self._next_id += 1
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        while True:
            try:
                response = self._responses.get(timeout=timeout)
            except queue.Empty as exc:
                raise McpClientError(f"timeout waiting for {method}") from exc
            if response.get("id") == request_id:
                if self.verbose:
                    print(f"{method}: {json.dumps(response, ensure_ascii=False)[:2000]}")
                return response
            if self.verbose:
                print(f"ignored message: {json.dumps(response, ensure_ascii=False)[:1000]}")

    def notify(self, method: str, params: dict[str, Any]) -> None:
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def close(self) -> None:
        process = self._process
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
        self._process = None

    def __enter__(self) -> "McpClient":
        self.start()
        return self

    def __exit__(self, exc_type: object, exc: object, tb: object) -> None:
        self.close()

    def _send(self, message: dict[str, Any]) -> None:
        process = self._process
        if process is None or process.stdin is None:
            raise McpClientError("server is not running")
        process.stdin.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
        process.stdin.flush()

    def _read_stdout(self) -> None:
        process = self._process
        if process is None or process.stdout is None:
            return
        for raw in process.stdout:
            line = raw.strip()
            if not line:
                continue
            try:
                self._responses.put(json.loads(line))
            except json.JSONDecodeError:
                self._responses.put({"raw_stdout": line})

    def _read_stderr(self) -> None:
        process = self._process
        if process is None or process.stderr is None:
            return
        for raw in process.stderr:
            line = raw.rstrip()
            self._stderr_lines.append(line)
            if self.verbose:
                print(line)
            if "Started JdbcMcpServerApplication" in line:
                self._started.set()


def server_env(base_env: dict[str, str], prefix: str, schema: str | None) -> dict[str, str]:
    env = os.environ.copy()
    env.update(base_env)
    missing = []
    for suffix in ("URL", "USERNAME", "PASSWORD"):
        key = f"{prefix}_{suffix}"
        if not env.get(key):
            missing.append(key)
    if missing:
        raise McpClientError("missing environment variables: " + ", ".join(missing))

    env["JDBC_URL"] = env[f"{prefix}_URL"]
    env["JDBC_USERNAME"] = env[f"{prefix}_USERNAME"]
    env["JDBC_PASSWORD"] = env[f"{prefix}_PASSWORD"]
    effective_schema = schema or env.get(f"{prefix}_SCHEMA")
    if effective_schema:
        env["JDBC_DEFAULT_SCHEMA"] = effective_schema
    env.setdefault("JDBC_USAGE_INDEX_ON_STARTUP", "false")
    return env
