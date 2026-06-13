package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Storage-level operations for the local SQLite catalog.
 */
@Service
public class CatalogStorageService {

    private final DataSource dataSource;

    public CatalogStorageService(@Qualifier("usageDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Flushes the WAL into the main database file before the catalog is copied or distributed.
     */
    public void checkpointForDistribution() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!rs.next()) {
                throw new SQLException("SQLite WAL checkpoint returned no status");
            }
            if (rs.getInt(1) != 0) {
                throw new SQLException("SQLite WAL checkpoint could not complete because the "
                        + "catalog is busy");
            }
        }
    }
}
