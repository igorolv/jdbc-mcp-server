# Connecting an AI Client

How to register the server with each MCP client, and how to run it by hand or in Docker. The
[README Quickstart](../README.md#quickstart) has the short version.

- [Claude Code](#claude-code)
- [Codex CLI](#codex-cli)
- [OpenCode](#opencode)
- [VS Code with GitHub Copilot](#vs-code-with-github-copilot)
- [GitHub Copilot CLI](#github-copilot-cli)
- [Other clients](#other-clients)
- [Tips for every client](#tips-for-every-client)
- [Running by hand](#running-by-hand)
- [Docker](#docker)

The server is a local stdio process, so every MCP client registers it the same way: the command is
`java`, the arguments are `-jar <absolute-path>/jdbc-mcp-server.jar`, and there is no environment to
set. The databases come from [`connections.json`](connections.md); credentials are kept
out of the client config on purpose — see
[why](read-only.md#why-credentials-live-in-a-file-not-in-environment-variables). Add
`JDBC_MCP_CONNECTIONS_FILE` only if you keep the file somewhere other than the default path.

| Client | Where the server is registered |
|---|---|
| [Claude Code](#claude-code) | `claude mcp add` → `~/.claude.json` (user) or `.mcp.json` (project) |
| [Codex CLI](#codex-cli) | `~/.codex/config.toml` |
| [OpenCode](#opencode) | `~/.config/opencode/opencode.json` (global) or `opencode.json` (project) |
| [VS Code with GitHub Copilot](#vs-code-with-github-copilot) | `.vscode/mcp.json` (workspace) or the user `mcp.json` |
| [GitHub Copilot CLI](#github-copilot-cli) | `~/.copilot/mcp-config.json` |
| [Cursor, Claude Desktop, Qwen Code](#other-clients) | the client's `mcpServers` JSON |

Once registered, ask the agent to call `listConnections`; it should list the entries of your
`connections.json`.

## Claude Code

```bash
claude mcp add --scope user jdbc -- java -jar /path/to/jdbc-mcp-server.jar
```

`--scope user` makes the server available in every project (stored in `~/.claude.json`); without it
the server is added to the current project only. The `--` separates Claude Code's own options from
the server command. `claude mcp list` shows whether the server started; inside a session, `/mcp`
shows its status and reconnects it after you edit `connections.json`.

To share the registration with a team, commit a project-scoped `.mcp.json`:

```json
{
  "mcpServers": {
    "jdbc": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/jdbc-mcp-server.jar"]
    }
  }
}
```

## Codex CLI

```bash
codex mcp add jdbc -- java -jar /path/to/jdbc-mcp-server.jar
```

or directly in `~/.codex/config.toml`:

```toml
[mcp_servers.jdbc]
command = "java"
args = ["-jar", "/path/to/jdbc-mcp-server.jar"]
# rebuildCatalog on a large schema can take minutes; the default tool timeout is 60 s
tool_timeout_sec = 600
```

On Windows, write the path as a TOML literal string so backslashes need no escaping:
`args = ["-jar", 'C:\tools\jdbc-mcp-server.jar']`. Codex gives a server 10 s to start
(`startup_timeout_sec`); the JVM usually needs 2–3 s, but raise it on a slow machine — a server that
misses the deadline is silently left out.

## OpenCode

In `~/.config/opencode/opencode.json` (or `opencode.jsonc`), or a project's `opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jdbc": {
      "type": "local",
      "command": ["java", "-jar", "/path/to/jdbc-mcp-server.jar"],
      "enabled": true
    }
  }
}
```

`command` is one array holding the program and its arguments. OpenCode waits 5 s for the tool list
by default; add `"timeout": 15000` (milliseconds) if the server shows up without tools on a slow
start.

## VS Code with GitHub Copilot

In `.vscode/mcp.json` for one workspace, or in the user-level `mcp.json` opened
with the **MCP: Open User Configuration** command for all workspaces:

```json
{
  "servers": {
    "jdbc": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/jdbc-mcp-server.jar"]
    }
  }
}
```

The top-level key is `servers`, not `mcpServers`. The tools are used by Copilot Chat in agent mode.
**MCP: List Servers** starts, stops and restarts the server and shows its output.

## GitHub Copilot CLI

In `~/.copilot/mcp-config.json` (or interactively with `/mcp add`):

```json
{
  "mcpServers": {
    "jdbc": {
      "type": "local",
      "command": "java",
      "args": ["-jar", "/path/to/jdbc-mcp-server.jar"],
      "tools": ["*"]
    }
  }
}
```

A project can also carry the configuration in `.mcp.json` or `.github/mcp.json`.

## Other clients

Cursor (`.cursor/mcp.json` or `~/.cursor/mcp.json`), Claude Desktop
(`claude_desktop_config.json`) and Qwen Code (`~/.qwen/settings.json`) all use the common
`mcpServers` shape:

```json
{
  "mcpServers": {
    "jdbc": {
      "command": "java",
      "args": ["-jar", "/path/to/jdbc-mcp-server.jar"]
    }
  }
}
```

## Tips for every client

- **Use absolute paths.** The client picks the working directory, so a relative jar path breaks.
  In JSON, write Windows paths with forward slashes (`C:/tools/jdbc-mcp-server.jar`) or doubled
  backslashes.
- **Java 21+.** If `java` on the `PATH` is older, put the full path of a JDK 21+ binary in
  `command`, e.g. `C:/Users/me/.jdks/jdk-21/bin/java.exe` or `/usr/lib/jvm/java-21/bin/java`.
- **Restart after editing `connections.json`** — the file is read once at startup.
- **When the client only says "failed to start"**, run the same command in a terminal; see
  [Checking the configuration](connections.md#checking-a-configuration).
- **Docker instead of a local JDK:** the command is `docker` with the arguments
  `run -i --rm -v /home/me/.jdbc-mcp-server:/data ghcr.io/igorolv/jdbc-mcp-server:latest` — see
  [Docker](#docker).

## Running by hand

```bash
java -jar jdbc-mcp-server.jar
```

(Use `build/libs/jdbc-mcp-server.jar` if you built it locally, or the file downloaded from
[Releases](https://github.com/igorolv/jdbc-mcp-server/releases/latest).)

The server immediately starts listening for MCP over stdin/stdout; no port is opened. Logs are
written to stderr and to `<data-dir>/logs/`. Running it by hand is mostly useful to
[check a configuration](connections.md#checking-a-configuration); `Ctrl-D` stops it.

## Docker

The image is published to GHCR with every release. Mount the directory holding `connections.json`
at `/data` — it is also where the server keeps its local catalogs and logs:

```bash
docker run -i --rm -v ~/.jdbc-mcp-server:/data ghcr.io/igorolv/jdbc-mcp-server:latest
```

The same command is what an MCP client should launch (`-i` keeps stdin open for the stdio
transport). JDBC URLs in `connections.json` must be reachable from inside the container: use the
database host name, not `localhost`, or add `--network host` on Linux. SQLite files and driver jars
must be inside the mounted directory or mounted separately — see
[Running in Docker](connections.md#running-in-docker). To build the image locally:

```bash
docker build -t jdbc-mcp-server .
```
