from .base import DbProfile
from .mssql import MSSQL
from .oracle import ORACLE
from .postgresql import POSTGRESQL

PROFILES = {
    ORACLE.name: ORACLE,
    POSTGRESQL.name: POSTGRESQL,
    MSSQL.name: MSSQL,
}

__all__ = ["DbProfile", "PROFILES"]
