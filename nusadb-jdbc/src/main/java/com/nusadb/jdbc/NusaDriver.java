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
        // A `?key=value&...` query string (after the database) carries TLS options; strip it before
        // the database name and merge it over the JDBC Properties.
        java.util.Map<String, String> params = new java.util.HashMap<>();
        int qmark = rest.indexOf('?');
        if (qmark >= 0) {
            for (String pair : rest.substring(qmark + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                if (eq >= 0) {
                    params.put(pair.substring(0, eq).toLowerCase(), pair.substring(eq + 1));
                } else {
                    params.put(pair.toLowerCase(), "true");
                }
            }
            rest = rest.substring(0, qmark);
        }
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        if (slash >= 0 && slash + 1 < rest.length()) {
            database = rest.substring(slash + 1);
        }
        // host[:port], or several separated by commas for failover.
        java.util.List<HostSelector.Host> hosts = HostSelector.parse(authority, host, port);

        Wire.Options o = new Wire.Options();
        o.host = hosts.get(0).name();
        o.port = hosts.get(0).port();
        o.user = user;
        o.database = database;
        o.password = password;
        o.tls = flag(option(params, info, "tls"));
        o.tlsInsecure = flag(option(params, info, "tlsInsecure"));
        o.tlsCa = option(params, info, "tlsCa");
        o.tlsDomain = option(params, info, "tlsDomain");
        // connectTimeout bounds TCP connect + TLS + login; the loginTimeout option, then
        // DriverManager.setLoginTimeout, apply when it is not given. socketTimeout is the default
        // read timeout (Connection.setNetworkTimeout).
        int login = seconds(option(params, info, "loginTimeout"), DriverManager.getLoginTimeout(), "loginTimeout") / 1000;
        o.connectTimeoutMillis = seconds(option(params, info, "connectTimeout"),
                login > 0 ? login : DEFAULT_CONNECT_TIMEOUT_SECONDS, "connectTimeout");
        o.socketTimeoutMillis = seconds(option(params, info, "socketTimeout"), 0, "socketTimeout");
        o.tcpKeepAlive = flag(option(params, info, "tcpKeepAlive"));
        int fetchSize = count(option(params, info, "defaultRowFetchSize"), 0, "defaultRowFetchSize");
        String schema = option(params, info, "currentSchema");
        String applicationName = option(params, info, "ApplicationName");
        boolean rewrite = flag(option(params, info, "rewriteBatchedInserts"));
        boolean untypedStrings = untypedStrings(option(params, info, "stringType"));
        NusaConnection.CallMode callMode = callMode(option(params, info, "escapeSyntaxCallMode"));
        int statementCache = count(option(params, info, "preparedStatementCacheQueries"), 256, "preparedStatementCacheQueries");
        HostSelector.Target target = HostSelector.Target.parse(option(params, info, "targetServerType"));
        boolean loadBalance = flag(option(params, info, "loadBalanceHosts"));
        int recheck = count(option(params, info, "hostRecheckSeconds"), 10, "hostRecheckSeconds");
        Wire wire = HostSelector.connect(hosts, o, target, loadBalance, recheck);
        NusaConnection c = new NusaConnection(wire, rewrite, untypedStrings, callMode, statementCache);
        c.defaultFetchSize = fetchSize;
        c.url = withoutPassword(url);
        c.user = user;
        c.applicationName(applicationName);
        if (schema != null && !schema.isBlank()) {
            try {
                c.searchPath(decode(schema));
            } catch (SQLException e) {
                c.close();
                throw e;
            }
        }
        return c;
    }

    /** The URL with the password of a {@code user:password@} part left out. */
    static String withoutPassword(String url) {
        String rest = url.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        int at = rest.lastIndexOf('@', slash >= 0 ? slash : rest.length());
        if (at < 0) {
            return url;
        }
        int colon = rest.indexOf(':');
        if (colon < 0 || colon > at) {
            return url;
        }
        return PREFIX + rest.substring(0, colon) + rest.substring(at);
    }

    /** A URL query value with its %XX escapes (a comma written as %2C, say) decoded. */
    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** A non-negative whole number option, or {@code fallback} when it is not given. */
    private static int count(String value, int fallback, String name) throws SQLException {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(value.trim());
            if (n >= 0) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new SQLException("nusadb: " + name + " must be a whole number >= 0: " + value, "08001");
    }

    /** {@code escapeSyntaxCallMode}: {@code select} (the default), {@code callIfNoReturn} or {@code call}. */
    private static NusaConnection.CallMode callMode(String value) throws SQLException {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("select")) {
            return NusaConnection.CallMode.SELECT;
        }
        if (value.equalsIgnoreCase("callIfNoReturn")) {
            return NusaConnection.CallMode.CALL_IF_NO_RETURN;
        }
        if (value.equalsIgnoreCase("call")) {
            return NusaConnection.CallMode.CALL;
        }
        throw new SQLException("nusadb: escapeSyntaxCallMode must be select, callIfNoReturn or call: " + value, "08001");
    }

    /** {@code stringType}: {@code text} (the default) or {@code unspecified}. */
    private static boolean untypedStrings(String value) throws SQLException {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("text") || value.equalsIgnoreCase("varchar")) {
            return false;
        }
        if (value.equalsIgnoreCase("unspecified")) {
            return true;
        }
        throw new SQLException("nusadb: stringType must be text or unspecified: " + value, "08001");
    }

    /** Default connect + login budget, so an unreachable host fails instead of hanging a pool. */
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    /** A URL query option (keys are matched case-insensitively) or, failing that, a JDBC property. */
    private static String option(java.util.Map<String, String> params, Properties info, String key) {
        String v = params.get(key.toLowerCase());
        if (v != null) {
            return v;
        }
        for (String name : info.stringPropertyNames()) {
            if (name.equalsIgnoreCase(key)) {
                return info.getProperty(name);
            }
        }
        return null;
    }

    private static int seconds(String value, int fallback, String name) throws SQLException {
        if (value == null || value.isEmpty()) {
            return fallback * 1000;
        }
        try {
            int s = Integer.parseInt(value.trim());
            if (s < 0) {
                throw new NumberFormatException(value);
            }
            return s * 1000;
        } catch (NumberFormatException e) {
            throw new SQLException("nusadb: " + name + " must be a non-negative number of seconds: " + value,
                    "08001", e);
        }
    }

    /** A URL/property flag is on unless absent, empty, or one of 0/off/false/no. */
    private static boolean flag(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String v = value.toLowerCase();
        return !(v.equals("0") || v.equals("off") || v.equals("false") || v.equals("no"));
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        DriverPropertyInfo user = new DriverPropertyInfo("user", info.getProperty("user", "nusadb-root"));
        user.description = "The database user.";
        DriverPropertyInfo password = new DriverPropertyInfo("password", info.getProperty("password"));
        password.description = "The user's password (for a SCRAM-authenticated server).";
        return new DriverPropertyInfo[] {
            user, password,
            property(info, "tls", "false", "Connect over TLS 1.2+."),
            property(info, "tlsCa", null, "PEM file with the CA (or self-signed certificate) to trust."),
            property(info, "tlsDomain", null, "Host name to verify the server certificate against."),
            property(info, "tlsInsecure", "false", "Skip certificate verification (development only)."),
            property(info, "connectTimeout", Integer.toString(DEFAULT_CONNECT_TIMEOUT_SECONDS),
                    "Seconds allowed for TCP connect, TLS and login (0 = no limit)."),
            property(info, "loginTimeout", "0",
                    "Seconds allowed to log in when connectTimeout is not given (0 = DriverManager's login timeout)."),
            property(info, "socketTimeout", "0", "Seconds to wait for any reply before the connection is closed (0 = forever)."),
            property(info, "tcpKeepAlive", "false", "Send TCP keep-alive probes on an idle connection."),
            property(info, "currentSchema", null,
                    "Schema (or comma-separated schemas) the session's search path starts with."),
            property(info, "defaultRowFetchSize", "0",
                    "Fetch size of every new statement (0 = read all rows at once)."),
            property(info, "ApplicationName", null, "Name of the application, reported by getClientInfo."),
            property(info, "targetServerType", "any",
                    "Role the server must have, of the URL's hosts: any, primary, secondary, preferPrimary or preferSecondary."),
            property(info, "loadBalanceHosts", "false", "Try the URL's hosts in random order instead of URL order."),
            property(info, "hostRecheckSeconds", "10",
                    "Seconds a host found down or of the wrong role is tried after the others."),
            property(info, "rewriteBatchedInserts", "false",
                    "Send a batch of single-row INSERTs as multi-row INSERTs (update counts become SUCCESS_NO_INFO)."),
            property(info, "stringType", "text",
                    "text: setString parameters are TEXT. unspecified: the server infers their type from the value."),
            property(info, "preparedStatementCacheQueries", "256",
                    "Server-side statements kept per connection for reuse by later PreparedStatements (0 = none)."),
            property(info, "escapeSyntaxCallMode", "select",
                    "How prepareCall runs {call f(..)}: select (SELECT f(..)), callIfNoReturn (CALL unless {? = call}) or call."),
        };
    }

    private static DriverPropertyInfo property(Properties info, String name, String fallback, String description) {
        DriverPropertyInfo p = new DriverPropertyInfo(name, info.getProperty(name, fallback));
        p.description = description;
        return p;
    }

    @Override
    public int getMajorVersion() {
        return Version.MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return Version.MINOR;
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
