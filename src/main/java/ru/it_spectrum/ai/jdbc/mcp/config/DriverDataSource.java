package ru.it_spectrum.ai.jdbc.mcp.config;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The minimal {@link DataSource} Hikari needs when it must not go through
 * {@link java.sql.DriverManager}: every connection comes straight from {@link Driver#connect}.
 *
 * <p>With {@code tolerateReadOnlyRefusal}, a driver's refusal to change the read-only flag is
 * ignored. Hikari sets the flag on every new connection, and some drivers reject any change once
 * connected — SQLite opened with {@code open_mode=1} refuses even {@code setReadOnly(false)} — which
 * would otherwise fail every connection the pool tries to create.
 */
record DriverDataSource(Driver driver, String url, Map<String, String> properties,
                        boolean tolerateReadOnlyRefusal) implements DataSource {

    @Override
    public Connection getConnection() throws SQLException {
        return connect(null, null);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return connect(username, password);
    }

    private Connection connect(String username, String password) throws SQLException {
        Properties info = new Properties();
        info.putAll(properties);
        if (username != null) info.setProperty("user", username);
        if (password != null) info.setProperty("password", password);
        Connection connection = driver.connect(url, info);
        if (connection == null) {
            throw new SQLException("Driver " + driver.getClass().getName() + " does not accept the URL");
        }
        return tolerateReadOnlyRefusal ? tolerant(connection) : connection;
    }

    private static Connection tolerant(Connection target) {
        return (Connection) Proxy.newProxyInstance(DriverDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        if (method.getName().equals("setReadOnly") && e.getCause() instanceof SQLException) {
                            return null; // the driver keeps the flag it was opened with
                        }
                        throw e.getCause();
                    }
                });
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) return type.cast(this);
        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }
}
