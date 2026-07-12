package com.nusadb.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * JDBC {@link Connection}. Supports both autocommit (the default — each statement runs on its own
 * implicit server transaction) and explicit transactions. When autocommit is off a {@code BEGIN} is
 * sent lazily before the first statement of a unit of work; {@link #commit()} and {@link #rollback()}
 * close it with {@code COMMIT}/{@code ROLLBACK} over the wire.
 */
final class NusaConnection implements Connection, NusaListener {
    final Wire wire;
    private boolean closed;
    private boolean autoCommit = true;
    private boolean inTransaction;
    private int savepointSeq;

    NusaConnection(Wire wire) {
        this.wire = wire;
    }

    /**
     * Opens a transaction lazily when autocommit is off and none is active. Called by statements
     * before they execute so the {@code BEGIN} brackets the first real statement of the unit of work.
     */
    private void beginIfNeeded() throws SQLException {
        if (!autoCommit && !inTransaction) {
            wire.simpleQuery("BEGIN");
            inTransaction = true;
        }
    }

    /** Runs a simple-protocol statement, opening a lazy transaction first when needed. */
    Wire.Result execSimple(String sql) throws SQLException {
        beginIfNeeded();
        return wire.simpleQuery(sql);
    }

    /** Runs a prepared portal, opening a lazy transaction first when needed. */
    Wire.Result execPrepared(String statement, List<byte[]> params) throws SQLException {
        beginIfNeeded();
        return wire.executePrepared(statement, params);
    }

    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("nusadb: feature not supported");
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("nusadb: connection is closed", "08003");
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        ensureOpen();
        return new NusaStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        ensureOpen();
        return new NusaPreparedStatement(this, positionalize(sql));
    }

    /**
     * Rewrites JDBC {@code ?} parameter markers into the server's positional {@code $1}, {@code $2}, …
     * form. Markers inside string literals, quoted identifiers, and comments are left untouched, and
     * SQL that already uses {@code $n} markers contains no {@code ?} and passes through unchanged.
     */
    static String positionalize(String sql) {
        if (sql.indexOf('?') < 0) {
            return sql;
        }
        int len = sql.length();
        StringBuilder out = new StringBuilder(len + 8);
        int n = 0;
        for (int i = 0; i < len; i++) {
            char ch = sql.charAt(i);
            if (ch == '\'' || ch == '"') {
                // String literal or quoted identifier: copy verbatim, honouring doubled-quote escapes.
                out.append(ch);
                for (i++; i < len; i++) {
                    char c = sql.charAt(i);
                    out.append(c);
                    if (c == ch) {
                        if (i + 1 < len && sql.charAt(i + 1) == ch) {
                            out.append(ch);
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (ch == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                // Line comment: copy through end of line.
                for (; i < len && sql.charAt(i) != '\n'; i++) {
                    out.append(sql.charAt(i));
                }
                if (i < len) {
                    out.append('\n');
                }
            } else if (ch == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                // Block comment: copy through the closing '*/'.
                out.append("/*");
                for (i += 2; i < len; i++) {
                    char c = sql.charAt(i);
                    out.append(c);
                    if (c == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                        out.append('/');
                        i++;
                        break;
                    }
                }
            } else if (ch == '?') {
                out.append('$').append(++n);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        if (!closed) {
            if (inTransaction) {
                try {
                    wire.simpleQuery("ROLLBACK");
                } catch (SQLException ignored) {
                    // best effort: the socket is about to close anyway
                }
                inTransaction = false;
            }
            closed = true;
            wire.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean getAutoCommit() {
        return autoCommit;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        ensureOpen();
        if (autoCommit == this.autoCommit) {
            return;
        }
        // Switching back to autocommit commits any open transaction (JDBC semantics).
        if (autoCommit && inTransaction) {
            wire.simpleQuery("COMMIT");
            inTransaction = false;
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        ensureOpen();
        if (autoCommit) {
            throw new SQLException("nusadb: commit called while autocommit is enabled", "25000");
        }
        if (inTransaction) {
            wire.simpleQuery("COMMIT");
            inTransaction = false;
        }
    }

    @Override
    public void rollback() throws SQLException {
        ensureOpen();
        if (autoCommit) {
            throw new SQLException("nusadb: rollback called while autocommit is enabled", "25000");
        }
        if (inTransaction) {
            wire.simpleQuery("ROLLBACK");
            inTransaction = false;
        }
    }

    @Override
    public boolean isValid(int timeout) {
        if (closed) {
            return false;
        }
        try {
            wire.simpleQuery("SELECT 1");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public String nativeSQL(String sql) {
        return sql;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        ensureOpen();
        return new NusaDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        // accepted but inert
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public void setCatalog(String catalog) {
        // Engine-per-database: a connection is bound to one database for its lifetime
        // (no in-connection switch), so this is inert.
    }

    @Override
    public String getCatalog() {
        return wire.database;
    }

    @Override
    public void setTransactionIsolation(int level) {
        // inert
    }

    @Override
    public int getTransactionIsolation() {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public SQLWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() {
        // none
    }

    @Override
    public void setHoldability(int holdability) {
        // inert
    }

    @Override
    public int getHoldability() {
        return java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public String getSchema() {
        return "public";
    }

    @Override
    public void setSchema(String schema) {
        // inert
    }

    // --- unsupported surface ---

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw unsupported();
    }

    @Override
    public Statement createStatement(int rsType, int rsConcurrency) throws SQLException {
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int rsType, int rsConcurrency) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int rsType, int rsConcurrency) throws SQLException {
        throw unsupported();
    }

    @Override
    public Statement createStatement(int rsType, int rsConcurrency, int rsHoldability) throws SQLException {
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int rsType, int rsConcurrency, int rsHoldability)
            throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int rsType, int rsConcurrency, int rsHoldability)
            throws SQLException {
        throw unsupported();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        ensureOpen();
        if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
            String augmented = NusaStatement.withReturning(positionalize(sql), "*");
            if (augmented != null) {
                return new NusaPreparedStatement(this, augmented, true);
            }
        }
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        ensureOpen();
        // Column indexes cannot be mapped to names without statement metadata, so return every
        // column; the caller reads the generated keys positionally.
        if (columnIndexes != null && columnIndexes.length > 0) {
            String augmented = NusaStatement.withReturning(positionalize(sql), "*");
            if (augmented != null) {
                return new NusaPreparedStatement(this, augmented, true);
            }
        }
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        ensureOpen();
        if (columnNames != null && columnNames.length > 0) {
            String augmented = NusaStatement.withReturning(positionalize(sql), String.join(", ", columnNames));
            if (augmented != null) {
                return new NusaPreparedStatement(this, augmented, true);
            }
        }
        return prepareStatement(sql);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw unsupported();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw unsupported();
    }

    /**
     * Quotes a savepoint identifier so an arbitrary name (with spaces, punctuation, or embedded
     * quotes) is emitted safely — the server folds a quoted identifier to its literal value, so the
     * same quoting on {@code SAVEPOINT} and {@code ROLLBACK TO}/{@code RELEASE} always matches.
     */
    private static String quoteIdentifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Savepoints require an open transaction; per the JDBC spec they are illegal in autocommit. */
    private NusaSavepoint establishSavepoint(NusaSavepoint sp) throws SQLException {
        ensureOpen();
        if (autoCommit) {
            throw new SQLException("nusadb: cannot set a savepoint in autocommit mode", "25000");
        }
        beginIfNeeded();
        wire.simpleQuery("SAVEPOINT " + quoteIdentifier(sp.sqlName));
        return sp;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return establishSavepoint(NusaSavepoint.unnamed(++savepointSeq));
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        if (name == null) {
            throw new SQLException("nusadb: savepoint name must not be null", "3B000");
        }
        return establishSavepoint(NusaSavepoint.named(name));
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        ensureOpen();
        if (!(savepoint instanceof NusaSavepoint sp)) {
            throw new SQLException("nusadb: savepoint was not created by this driver", "3B000");
        }
        wire.simpleQuery("ROLLBACK TO SAVEPOINT " + quoteIdentifier(sp.sqlName));
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        ensureOpen();
        if (!(savepoint instanceof NusaSavepoint sp)) {
            throw new SQLException("nusadb: savepoint was not created by this driver", "3B000");
        }
        wire.simpleQuery("RELEASE SAVEPOINT " + quoteIdentifier(sp.sqlName));
    }

    // --- asynchronous notifications (LISTEN/NOTIFY) — reached via unwrap(NusaListener.class) ---

    /** Quotes a string as a SQL literal (`'…'`, doubling embedded `'`) — used for a NOTIFY payload. */
    private static String quoteLiteral(String text) {
        return '\'' + text.replace("'", "''") + '\'';
    }

    @Override
    public void listen(String channel) throws SQLException {
        ensureOpen();
        wire.simpleQuery("LISTEN " + quoteIdentifier(channel));
    }

    @Override
    public void unlisten(String channel) throws SQLException {
        ensureOpen();
        String target = channel == null ? "*" : quoteIdentifier(channel);
        wire.simpleQuery("UNLISTEN " + target);
    }

    @Override
    public void notify(String channel, String payload) throws SQLException {
        ensureOpen();
        String sql = "NOTIFY " + quoteIdentifier(channel);
        if (payload != null) {
            sql += ", " + quoteLiteral(payload);
        }
        wire.simpleQuery(sql);
    }

    @Override
    public List<NusaNotification> getNotifications() throws SQLException {
        ensureOpen();
        return wire.drainNotifications();
    }

    @Override
    public NusaNotification pollNotification(int timeoutMillis) throws SQLException {
        ensureOpen();
        return wire.pollNotification(timeoutMillis);
    }

    @Override
    public Clob createClob() throws SQLException {
        throw unsupported();
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw unsupported();
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw unsupported();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw unsupported();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new SQLClientInfoException();
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        throw new SQLClientInfoException();
    }

    @Override
    public String getClientInfo(String name) {
        return null;
    }

    @Override
    public Properties getClientInfo() {
        return new Properties();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw unsupported();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw unsupported();
    }

    @Override
    public void abort(Executor executor) {
        close();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw unsupported();
    }

    @Override
    public int getNetworkTimeout() {
        return 0;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        // The COPY bulk-load API is reached via unwrap, bound to this connection.
        if (iface == NusaCopyManager.class) {
            return iface.cast(new NusaCopyManager(wire));
        }
        throw new SQLException("nusadb: not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this) || iface == NusaCopyManager.class;
    }
}
