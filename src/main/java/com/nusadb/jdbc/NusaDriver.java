package com.nusadb.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC {@link Driver} for NusaDB. URL form: {@code jdbc:nusadb://host:port/database}.
 *
 * <p>Connection properties {@code user} and {@code password} may be passed via the {@link Properties}
 * or as URL userinfo ({@code jdbc:nusadb://user:password@host:port/db}).
 */
public final class NusaDriver implements Driver {
    private static final String PREFIX = "jdbc:nusadb://";

    static {
        try {
            DriverManager.registerDriver(new NusaDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        String rest = url.substring(PREFIX.length());
        String user = info.getProperty("user", "nusadb-root");
        String password = info.getProperty("password", "nusadb-root");

        int at = rest.indexOf('@');
        if (at >= 0) {
            String userInfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                user = userInfo.substring(0, colon);
                password = userInfo.substring(colon + 1);
            } else if (!userInfo.isEmpty()) {
                user = userInfo;
            }
        }

        String host = "127.0.0.1";
        int port = 5678;
        String database = "nusadb";
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        if (slash >= 0 && slash + 1 < rest.length()) {
            database = rest.substring(slash + 1);
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            try {
                port = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new SQLException("nusadb: invalid port in URL", "08001", e);
            }
        } else if (!authority.isEmpty()) {
            host = authority;
        }

        Wire wire = Wire.connect(host, port, user, database, password);
        return new NusaConnection(wire);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        DriverPropertyInfo user = new DriverPropertyInfo("user", info.getProperty("user", "nusadb-root"));
        user.description = "The database user.";
        DriverPropertyInfo password = new DriverPropertyInfo("password", info.getProperty("password", "nusadb-root"));
        password.description = "The user's password (for a SCRAM-authenticated server).";
        return new DriverPropertyInfo[] {user, password};
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
