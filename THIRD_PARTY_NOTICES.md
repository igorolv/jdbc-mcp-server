# Third-party Notices

This project is licensed under the Apache License, Version 2.0. Runtime and test dependencies are
licensed by their respective owners under their own terms.

This file is informational. The dependency POMs and upstream license files remain authoritative.

## Runtime Dependencies

| Component | License |
|---|---|
| Spring Boot / Spring Framework components | Apache License 2.0 |
| Spring AI MCP Server | Apache License 2.0 |
| HikariCP | Apache License 2.0 |
| Xerial SQLite JDBC | Apache License 2.0 (bundles SQLite, public domain) |
| JSqlParser | Apache License 2.0 |
| PostgreSQL JDBC Driver | BSD 2-Clause License |
| Oracle JDBC Driver (`ojdbc11`) | Oracle Free Use Terms and Conditions (FUTC) |
| Oracle NLS Support (`orai18n`) | Oracle Free Use Terms and Conditions (FUTC) |

## Test Dependencies

| Component | License |
|---|---|
| JUnit / Spring Boot test components | Eclipse Public License 2.0 / Apache License 2.0, depending on component |
| Testcontainers | MIT License |
| Apache Commons Lang | Apache License 2.0 |
| Jackson Databind | Apache License 2.0 |

## Oracle JDBC Notes

Oracle JDBC artifacts are not licensed under this project's Apache-2.0 license. They are provided
by Oracle under the Oracle Free Use Terms and Conditions:

https://www.oracle.com/downloads/licenses/oracle-free-license.html

If you distribute a built fat jar that includes Oracle JDBC artifacts, include the applicable Oracle
license terms and do not remove Oracle or licensor notices from those artifacts.
