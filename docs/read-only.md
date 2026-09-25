# Read-only Protection

The server never writes to the databases it inspects. This page describes how that is enforced on
each engine, how to set up a read-only database user, when the client-side guard can be switched
off, and why database credentials are kept out of the environment.

- [Layers of protection](#layers-of-protection)
- [Maximum protection: use a read-only database user](#maximum-protection-use-a-read-only-database-user)
- [Disabling the guard](#disabling-the-guard)
- [Why credentials live in a file, not in environment variables](#why-credentials-live-in-a-file-not-in-environment-variables)

## Layers of protection

Protection is layered and is designed primarily for accidental `DELETE` / `DROP` statements from
an LLM, not for a malicious actor. A malicious actor already has the database URL, username, and
password — which is also why the server
[keeps credentials out of the environment](#why-credentials-live-in-a-file-not-in-environment-variables),
so that an agent does not casually acquire them.

1. **ReadOnlyGuard in project code.** Before sending SQL to the database, the server first parses it with JSqlParser and checks the AST. Only a single `SELECT`, `WITH`, or `EXPLAIN` is allowed. Write CTEs, `SELECT INTO`, and locking clauses such as `FOR UPDATE` are forbidden. If JSqlParser cannot parse dialect-specific SQL, the guard falls back to the older lexical check: first meaningful token, multi-statement rejection, comment skipping, and write-keyword detection outside strings and quoted identifiers.
2. **`connection.setReadOnly(true)`.** Set by Hikari and again by this server on each checkout.
3. **PostgreSQL: `default_transaction_read_only=on`.** Added to the JDBC URL automatically unless you already provided your own `options=`. Even server-side DDL is rejected.
4. **Oracle: JDBC read-only hint.** Oracle JDBC treats `setReadOnly(true)` mostly as an advisory hint. The client-side guard and a dedicated read-only database user are the primary Oracle protections. Oracle `EXPLAIN PLAN` writes a static plan to `PLAN_TABLE`; this server scopes those reads with a generated `STATEMENT_ID`.
5. **SQLite: the file is opened read-only.** `open_mode=1` is added to the URL unless you set `open_mode` yourself; SQLite then rejects every write.
6. **Firebird: server-enforced read-only transactions.** Jaybird turns `setReadOnly(true)` into read-only transactions, which the server enforces for DML and DDL alike.
7. **SQL Server: JDBC read-only hint plus SHOWPLAN estimated plans.** SQL Server also treats `setReadOnly(true)` as a hint. Use a least-privilege login/user for strong enforcement. `explainQuery` and `analyzePlan` use `SHOWPLAN_TEXT/XML`, which returns estimated plans without executing the statement.
8. **Generic JDBC: best-effort.** The guard, plus `setReadOnly(true)` where the driver honours it. Use a read-only URL option where the driver has one, or a read-only database user.

## Maximum protection: use a read-only database user

A dedicated user with read-only permissions keeps the database protected even when the guard is
disabled or bypassed.

**PostgreSQL:**

```sql
CREATE ROLE ai_readonly LOGIN PASSWORD 'strong-password';
GRANT CONNECT ON DATABASE mydb TO ai_readonly;
GRANT USAGE ON SCHEMA public TO ai_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ai_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO ai_readonly;
```

**Oracle:**

```sql
CREATE USER ai_readonly IDENTIFIED BY "strong-password";
GRANT CREATE SESSION TO ai_readonly;
GRANT SELECT ANY DICTIONARY TO ai_readonly;  -- for metadata
-- For each required table/view:
GRANT SELECT ON app_schema.customers TO ai_readonly;
-- ...or a role collecting all SELECT grants:
-- CREATE ROLE ai_ro_role; GRANT ai_ro_role TO ai_readonly;
```

**SQL Server:**

```sql
CREATE LOGIN ai_readonly WITH PASSWORD = 'strong-password';
CREATE USER ai_readonly FOR LOGIN ai_readonly;
GRANT SELECT ON SCHEMA::dbo TO ai_readonly;
GRANT VIEW DEFINITION TO ai_readonly; -- for object definitions and richer metadata
GRANT SHOWPLAN TO ai_readonly;        -- for explainQuery/analyzePlan estimated plans
```

## Disabling the guard

If you need to call, for example, a stored procedure with read-only semantics that the guard does
not allow, you can disable client-side validation:

```json
"readonlyGuard": "off"
```

Connection-level protections (`setReadOnly` and, on PostgreSQL, `default_transaction_read_only`)
remain enabled. On Oracle and SQL Server, `setReadOnly` is best-effort; use a read-only database
user for the strongest guarantee.

## Why credentials live in a file, not in environment variables

The point of this server is that the agent reaches the database *only* through it: every statement
goes through the read-only guard, every result is capped by `maxRows`, and nothing but
`SELECT` / `WITH` / `EXPLAIN` gets through.

Credentials in environment variables undermine exactly that. They are set on the server process by
the MCP client, which means they also sit in the client's own configuration — a file agents read and
edit as a matter of routine — and in the environment of whatever shell launched it. An agent that
has seen a URL, a user and a password does not need the tools any more: `psql`, `sqlplus`, `sqlcmd`
or three lines of Python connect straight to the database, with no guard, no row cap and no trace in
this server's log.

So the server accepts no database credentials from the environment at all — there are no `JDBC_URL`
/ `JDBC_USERNAME` / `JDBC_PASSWORD` variables. They live in `connections.json`, which only the server
reads.

Be clear about what that does and does not buy:

- It removes the easy path. Credentials stop being part of the material an agent routinely handles:
  MCP client configs, shell environment, `env` dumps in logs and bug reports.
- **It is not a sandbox.** An agent with shell access running as you can read the file; `chmod 600`
  keeps out other users, not a process running as your user.
- The guarantee that survives everything is a
  [read-only database user](#maximum-protection-use-a-read-only-database-user). The file narrows the
  attack surface; the database's own permissions close it.

For the same reason, prefer a literal password in the file over a `${VAR}` reference whose variable
would be set in the MCP client's `env` block — that puts the secret straight back where the agent
looks. `${VAR}` earns its place when the value is injected from outside the agent's reach (a systemd
unit, a wrapper script, a secret manager), or when the file itself is shared or committed and the
secret must not be.
