# Security Policy

## Supported Versions

The current version on the `main` branch is supported.

## Reporting a Vulnerability

Do not publish vulnerability details in an open issue.

If GitHub private vulnerability reporting is enabled for this repository, use it. If it is not
available, open an issue without exploit details and ask the maintainer for a private channel to
share technical information.

Include:

- affected version or commit;
- brief risk summary;
- minimal reproduction steps, if they can be shared safely;
- expected impact;
- known workaround, if any.

This project handles JDBC credentials and real databases, so reports about read-only guard bypass,
credential leaks, write-query execution, and unsafe SQL parameter handling are especially important.
