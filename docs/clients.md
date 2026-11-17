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
- [JBang](#jbang)
- [Docker](#docker)
- [Streamable HTTP](#streamable-http)

By default the server is a local stdio process, so every MCP client registers it the same way: the command is
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
- **JBang instead of a downloaded jar:** the command is `jbang` with the arguments
  `--quiet jdbc-mcp-server@igorolv/jdbc-mcp-server` — see [JBang](#jbang).
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

## JBang

[JBang](https://www.jbang.dev/) downloads the released jar, and a JDK when none of version 21 or
newer is installed, then starts the server as an ordinary local process:

```bash
jbang --quiet jdbc-mcp-server@igorolv/jdbc-mcp-server
```

Without JBang installed, Node.js can fetch it:

```bash
npx -y @jbangdev/jbang --quiet jdbc-mcp-server@igorolv/jdbc-mcp-server
```

Either command is what an MCP client should launch, for example in the common `mcpServers` shape:

```json
{
  "mcpServers": {
    "jdbc": {
      "command": "jbang",
      "args": ["--quiet", "jdbc-mcp-server@igorolv/jdbc-mcp-server"]
    }
  }
}
```

- The server reads the same `connections.json` and keeps its catalogs and logs in the same data
  directory as when started with `java -jar`.
- Keep `--quiet`: it stops JBang from printing its own messages while the client talks to the
  server over stdin/stdout.
- The first start downloads the jar, and possibly a JDK, so a client with a short startup timeout
  may report a failure once. Run the command in a terminal first, stop it with `Ctrl-D`, then
  register it.
- The alias names one released version; `jbang-catalog.json` in this repository is updated with
  every release. JBang caches what it downloads: if it keeps starting an older version, run
  `jbang cache clear`.

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

## Streamable HTTP

By default the client starts the server as a child process (stdio): one process per client, and the
tool calls of a session run one after another. When one server should serve several clients — a
backend service, several agents, a team machine — or run tool calls in parallel, start it on its
own with the streamable HTTP transport:

```bash
JDBC_MCP_TRANSPORT=http java -jar jdbc-mcp-server.jar
```

```powershell
$env:JDBC_MCP_TRANSPORT = "http"; java -jar jdbc-mcp-server.jar
```

The MCP endpoint is `http://127.0.0.1:8090/mcp`. The server reads nothing from stdin and runs until
it is stopped. `connections.json` is still the only place databases are configured.

| Variable | Default | Meaning |
|---|---|---|
| `JDBC_MCP_TRANSPORT` | `stdio` | `http` turns on this transport |
| `JDBC_MCP_HTTP_ADDRESS` | `127.0.0.1` | Listen address; `0.0.0.0` for all interfaces |
| `JDBC_MCP_HTTP_PORT` | `8090` | Listen port |
| `JDBC_MCP_HTTP_TOKEN` | — | When set, every request needs `Authorization: Bearer <token>` |
| `JDBC_MCP_HTTP_ALLOWED_HOSTS` | — | `Host` header values accepted besides `localhost`, `127.0.0.1`, `[::1]` (comma-separated; `*` turns the check off) |
| `JDBC_MCP_HTTP_ALLOWED_ORIGINS` | — | Browser origins accepted besides loopback ones (comma-separated; `*` for any) |

**Parallel tool calls.** Over HTTP each request gets its own response, so tool calls run in
parallel — those of different clients and those of one session. (Over stdio they stay sequential:
the MCP Java SDK can lose responses when concurrent calls finish together on the one stdout stream.)
Concurrency against a database is bounded by that connection's `poolMaximumSize` and
`queryTimeoutSeconds`; size them for the expected load.

**Who may connect.** The server has no user accounts; every caller can use every configured
connection, read-only. Keep it on loopback unless the network around it is trusted:

- While bound to a loopback address, a request whose `Host` header is not a loopback name is
  refused with `403`. A web page that rebinds its DNS name to `127.0.0.1` still sends its own name,
  so this stops DNS-rebinding attacks from a browser.
- A request with a browser `Origin` that is not a loopback origin is refused with `403`. MCP clients
  send no `Origin` and are not affected.
- With `JDBC_MCP_HTTP_TOKEN` set, a request without the right `Authorization: Bearer` header is
  refused with `401`. Set a token whenever the server listens on a non-loopback address, and list
  the names clients use for the machine in `JDBC_MCP_HTTP_ALLOWED_HOSTS`. The token protects access
  to the server only — database credentials still never leave `connections.json`.

**Registering the URL.** Clients register a URL instead of a command:

```bash
claude mcp add --transport http jdbc http://127.0.0.1:8090/mcp
```

```json
{
  "mcpServers": {
    "jdbc": {
      "type": "http",
      "url": "http://127.0.0.1:8090/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

The `headers` entry is needed only with `JDBC_MCP_HTTP_TOKEN`. VS Code (`"servers"`) uses the same
`type: "http"` / `url` shape. From Java, Spring AI's MCP client connects with
`spring.ai.mcp.client.streamable-http.connections.jdbc.url=http://127.0.0.1:8090`.

**Docker.** Listen on all interfaces inside the container, publish the port on loopback, and set a
token:

```bash
docker run -d --rm -p 127.0.0.1:8090:8090 -v ~/.jdbc-mcp-server:/data \
  -e JDBC_MCP_TRANSPORT=http -e JDBC_MCP_HTTP_ADDRESS=0.0.0.0 \
  -e JDBC_MCP_HTTP_TOKEN=<token> -e JDBC_MCP_HTTP_ALLOWED_HOSTS=localhost \
  ghcr.io/igorolv/jdbc-mcp-server:latest
```

