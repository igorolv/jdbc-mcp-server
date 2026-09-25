from .base import DbProfile
from .mssql import MSSQL
from .oracle import ORACLE
from .postgresql import POSTGRESQL
from .sqlite import SQLITE

PROFILES = {
    ORACLE.name: ORACLE,
    POSTGRESQL.name: POSTGRESQL,
    MSSQL.name: MSSQL,
    SQLITE.name: SQLITE,
}

__all__ = ["DbProfile", "PROFILES"]
