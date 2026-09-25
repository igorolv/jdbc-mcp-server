# Error Format

What a tool returns when it fails, and how an agent should react to each kind of error. MCP
resource reads use the standard MCP error codes instead — see
[MCP Resources](../README.md#mcp-resources).

All tools return errors in the same shape: JSON with `error` and `kind` fields.

```json
{"error": "Only SELECT / WITH / EXPLAIN statements are allowed", "kind": "rejected"}
```

| `kind` | When |
|---|---|
| `sql` | The database returned a `SQLException` for syntax, missing object, missing permission, and similar cases |
| `argument` | Invalid tool argument |
| `unsupported` | The connection's engine cannot answer this at all (e.g. plans on a generic JDBC connection); retrying with other arguments will not help |
| `rejected` | The read-only guard blocked the query before it reached the database |
| `not_found` | `getViewDefinition`, `getRoutineDefinition`, or `getTriggerDefinition` found nothing, or the table named in `describeTable`, `tableContext`, `findJoinPaths`, or `schemaLint` (with `table`) does not exist. The response body also includes `missing` and `name` |
| `driver` / `unexpected` / `plan_parse` | Internal driver failure, unhandled failure, or plan parsing failure |

`validateQuery` uses its own shape, without `kind`; `valid` is the discriminator.

```json
{"valid": true,  "parameters": 1, "columns": 3}
{"valid": false, "stage": "guard|params|driver", "error": "..."}
```
