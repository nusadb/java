package com.nusadb.jdbc;

import java.io.PrintWriter;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} for NusaDB, for pools and containers that are configured with a data source
 * class and bean properties rather than a JDBC URL (HikariCP {@code dataSourceClassName}, JNDI,
 * Jakarta EE). It opens a new physical connection on every call; pooling is the pool's job.
 *
 * <pre>
 * NusaDataSource ds = new NusaDataSource();
 * ds.setServerName("db.example.com");
 * ds.setDatabaseName("app");
 * ds.setUser("app");
 * ds.setPassword(secret);
 * </pre>
 *
 * Setting {@link #setUrl url} instead takes host, port, database and URL options from it; the bean
 * properties set explicitly still apply on top.
 */
public class NusaDataSource implements DataSource, Serializable {
    private static final long serialVersionUID = 1L;

    private String url;
    private String serverName = "127.0.0.1";
    private int portNumber = 5678;
    private String databaseName = "nusadb";
    private String user;
    private String password;
    private final Properties options = new Properties();
    private int loginTimeout;
    private transient PrintWriter logWriter;

    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(user, password);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties info = new Properties();
        info.putAll(options);
        if (username != null) {
            info.setProperty("user", username);
        }
        if (password != null) {
            info.setProperty("password", password);
        }
        if (loginTimeout > 0 && !info.containsKey("connectTimeout")) {
            info.setProperty("connectTimeout", Integer.toString(loginTimeout));
        }
        Connection c = new NusaDriver().connect(effectiveUrl(), info);
        if (c == null) {
            throw new SQLException("nusadb: not a jdbc:nusadb:// URL: " + url, "08001");
        }
        return c;
    }

    private String effectiveUrl() {
        if (url != null && !url.isEmpty()) {
            return url;
        }
        return "jdbc:nusadb://" + serverName + ":" + portNumber + "/" + databaseName;
    }

    // --- bean properties -----------------------------------------------------------------

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public int getPortNumber() {
        return portNumber;
    }

    public void setPortNumber(int portNumber) {
        this.portNumber = portNumber;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isTls() {
        return Boolean.parseBoolean(options.getProperty("tls"));
    }

    public void setTls(boolean tls) {
        options.setProperty("tls", Boolean.toString(tls));
    }

    public String getTlsCa() {
        return options.getProperty("tlsCa");
    }

    public void setTlsCa(String path) {
        set("tlsCa", path);
    }

    public String getTlsDomain() {
        return options.getProperty("tlsDomain");
    }

    public void setTlsDomain(String domain) {
        set("tlsDomain", domain);
    }

    public boolean isTlsInsecure() {
        return Boolean.parseBoolean(options.getProperty("tlsInsecure"));
    }

    public void setTlsInsecure(boolean insecure) {
        options.setProperty("tlsInsecure", Boolean.toString(insecure));
    }

    /** Seconds allowed for TCP connect, TLS and login. */
    public int getConnectTimeout() {
        return Integer.parseInt(options.getProperty("connectTimeout",
                Integer.toString(NusaDriver.DEFAULT_CONNECT_TIMEOUT_SECONDS)));
    }

    public void setConnectTimeout(int seconds) {
        options.setProperty("connectTimeout", Integer.toString(seconds));
    }

    /** Seconds to wait for any reply before the connection is closed (0 = forever). */
    public int getSocketTimeout() {
        return Integer.parseInt(options.getProperty("socketTimeout", "0"));
    }

    public void setSocketTimeout(int seconds) {
        options.setProperty("socketTimeout", Integer.toString(seconds));
    }

    public boolean isRewriteBatchedInserts() {
        return Boolean.parseBoolean(options.getProperty("rewriteBatchedInserts"));
    }

    public void setRewriteBatchedInserts(boolean rewrite) {
        options.setProperty("rewriteBatchedInserts", Boolean.toString(rewrite));
    }

    private void set(String key, String value) {
        if (value == null) {
            options.remove(key);
        } else {
            options.setProperty(key, value);
        }
    }

    // --- CommonDataSource ------------------------------------------------------------------

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("nusadb: the driver does not log through java.util.logging");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("nusadb: not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
