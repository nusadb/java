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
 * JDBC {@link Connection}. Supports both autocommit (the default, where each statement runs on its own
 * implicit server transaction) and explicit transactions. When autocommit is off a {@code BEGIN} is
 * sent lazily before the first statement of a unit of work; {@link #commit()} and {@link #rollback()}
 * close it with {@code COMMIT}/{@code ROLLBACK} over the wire.
 */
final class NusaConnection implements Connection, NusaListener {
    final Wire wire;
    /** Rewrite a batched single-row INSERT into multi-row INSERTs ({@code rewriteBatchedInserts}). */
    final boolean rewriteBatchedInserts;
    /**
     * {@code stringType=unspecified}: string parameters are sent untyped and the server infers their
     * type from the text, as drivers did before 0.2.1. By default they are declared {@code TEXT}.
     */
    final boolean untypedStrings;

    /** How {@code prepareCall} runs {@code {call f(..)}} ({@code escapeSyntaxCallMode}). */
    enum CallMode { SELECT, CALL_IF_NO_RETURN, CALL }

    final CallMode callMode;
    /** Server statements reused by later PreparedStatements with the same SQL. */
    final StatementCache statements;
    private volatile boolean closed;
    private boolean autoCommit = true;
    private boolean inTransaction;
    private int savepointSeq;
    /** The isolation last set through {@link #setTransactionIsolation}, or null when never set. */
    private Integer isolation;
    /** The fetch size every new statement starts with ({@code defaultRowFetchSize}, 0 = all rows at once). */
    int defaultFetchSize;
    /**
     * The {@code ApplicationName} client info. Kept by the driver: the server has no
     * {@code application_name} setting to report it to.
     */
    private volatile String applicationName;

    NusaConnection(Wire wire) {
        this(wire, false, false, CallMode.SELECT, 256);
    }

    NusaConnection(Wire wire, boolean rewriteBatchedInserts, boolean untypedStrings, CallMode callMode,
            int statementCache) {
        this.wire = wire;
        this.rewriteBatchedInserts = rewriteBatchedInserts;
        this.untypedStrings = untypedStrings;
        this.callMode = callMode;
        this.statements = new StatementCache(wire, statementCache);
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

    /** Starts a simple query whose rows are read lazily, opening a lazy transaction first when needed. */
    Wire.RowStream streamSimple(String sql) throws SQLException {
        beginIfNeeded();
        return wire.streamQuery(sql, !autoCommit);
    }

    /** Starts a prepared statement whose rows are read lazily, opening a lazy transaction first when needed. */
    Wire.RowStream streamPrepared(String statement, List<byte[]> params) throws SQLException {
        beginIfNeeded();
        return wire.streamPrepared(statement, params, !autoCommit);
    }

    /** Runs a prepared portal, opening a lazy transaction first when needed. */
    Wire.Result execPrepared(String statement, List<byte[]> params) throws SQLException {
        beginIfNeeded();
        return wire.executePrepared(statement, params);
    }

    /**
     * Runs a pipelined batch of prepared portals. Inside a transaction it is part of that
     * transaction. In autocommit the whole batch is one transaction, committed at the end and rolled
     * back if an entry fails, as a batch behaves on other servers; the server would otherwise commit
     * every entry on its own, about a millisecond each.
     */
    long[] execBatch(List<String> statements, List<List<byte[]>> rows) throws SQLException {
        return rows.isEmpty() ? new long[0] : batchTransaction(() -> wire.executeBatch(statements, rows));
    }

    /** Runs a pipelined {@code Statement} batch, in one transaction as {@link #execBatch}. */
    long[] execSimpleBatch(List<String> queries) throws SQLException {
        return queries.isEmpty() ? new long[0] : batchTransaction(() -> wire.simpleBatch(queries));
    }

    private long[] batchTransaction(NusaStatement.WireCall<long[]> batch) throws SQLException {
        if (!autoCommit) {
            beginIfNeeded();
            return batch.call();
        }
        wire.simpleQuery("BEGIN");
        long[] counts;
        try {
            counts = batch.call();
        } catch (SQLException e) {
            try {
                wire.simpleQuery("ROLLBACK");
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        }
        wire.simpleQuery("COMMIT");
        return counts;
    }

    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("nusadb: feature not supported");
    }

    private void ensureOpen() throws SQLException {
        if (isClosed()) {
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
        return new NusaPreparedStatement(this, prepared(sql));
    }

    /** A statement's SQL as prepared: JDBC escapes translated, then ? turned into $n. */
    private static String prepared(String sql) throws SQLException {
        return positionalize(JdbcEscapes.translate(sql));
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
        // A connection whose socket failed (I/O error, network timeout, abort) is closed too: JDBC
        // pools ask isClosed() to decide whether to hand it out again.
        return closed || wire.isBroken();
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        ensureOpen();
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
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("nusadb: isValid timeout must be >= 0", "22023");
        }
        if (isClosed()) {
            return false;
        }
        // A server that does not answer within the timeout costs the connection: the reply would
        // still arrive later and leave the stream out of step.
        return wire.ping(timeout * 1000);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        ensureOpen();
        return JdbcEscapes.translate(sql);
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        ensureOpen();
        return new NusaDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        ensureOpen();
        // A hint only (JDBC allows that): the server has no read-only transactions yet, so nothing
        // is enforced and isReadOnly() keeps reporting false.
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        ensureOpen();
        return false;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        ensureOpen();
        // Engine-per-database: a connection is bound to one database for its lifetime
        // (no in-connection switch), so this is inert.
    }

    @Override
    public String getCatalog() throws SQLException {
        ensureOpen();
        return wire.database;
    }

    /** The SQL spelling of a JDBC isolation level. */
    private static String isolationSql(int level) throws SQLException {
        switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED:
                return "READ UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED:
                return "READ COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ:
                return "REPEATABLE READ";
            case Connection.TRANSACTION_SERIALIZABLE:
                return "SERIALIZABLE";
            default:
                throw new SQLException("nusadb: unsupported transaction isolation level: " + level, "HY024");
        }
    }

    /**
     * Sets the isolation for the transactions this connection starts from now on. The server only
     * accepts a level before a transaction's first statement, so changing it mid-transaction is an
     * error rather than something that would silently apply later.
     */
    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        ensureOpen();
        String sql = isolationSql(level);
        if (inTransaction) {
            throw new SQLException(
                    "nusadb: cannot change the isolation level inside an open transaction", "25001");
        }
        wire.simpleQuery("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + sql);
        isolation = level;
    }

    /** The isolation the server reports for this session (the level the next transaction gets). */
    @Override
    public int getTransactionIsolation() throws SQLException {
        ensureOpen();
        String level;
        try {
            Wire.Result r = wire.simpleQuery("SHOW transaction_isolation");
            byte[] cell = r.rows.isEmpty() || r.rows.get(0).isEmpty() ? null : r.rows.get(0).get(0);
            level = cell == null ? "" : new String(cell, java.nio.charset.StandardCharsets.UTF_8);
        } catch (SQLException e) {
            // In an aborted transaction every statement fails until ROLLBACK; answer from what we set.
            if (isolation != null && "25P02".equals(e.getSQLState())) {
                return isolation;
            }
            throw e;
        }
        switch (level.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "read uncommitted":
                return Connection.TRANSACTION_READ_UNCOMMITTED;
            case "read committed":
                return Connection.TRANSACTION_READ_COMMITTED;
            case "repeatable read":
                return Connection.TRANSACTION_REPEATABLE_READ;
            case "serializable":
                return Connection.TRANSACTION_SERIALIZABLE;
            default:
                throw new SQLException("nusadb: server reported an unknown isolation level: " + level, "XX000");
        }
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        ensureOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        ensureOpen(); // pools call this when a connection is returned: a dead one must say so
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        ensureOpen();
    }

    @Override
    public int getHoldability() throws SQLException {
        ensureOpen();
        return java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    /** The schema unqualified names resolve to first: the head of the session's search path. */
    @Override
    public String getSchema() throws SQLException {
        ensureOpen();
        Wire.Result r = wire.simpleQuery("SELECT current_schema()");
        byte[] cell = r.rows.isEmpty() || r.rows.get(0).isEmpty() ? null : r.rows.get(0).get(0);
        return cell == null ? null : new String(cell, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Makes {@code schema} the whole search path; {@code null} restores the server's default path. */
    @Override
    public void setSchema(String schema) throws SQLException {
        ensureOpen();
        wire.simpleQuery("SET search_path TO " + (schema == null ? "DEFAULT" : quoteIdentifier(schema)));
    }

    /**
     * Sets the search path from the {@code currentSchema} option: a comma-separated list of schema
     * names, each taken literally unless it is already double-quoted.
     */
    void searchPath(String schemas) throws SQLException {
        StringBuilder sql = new StringBuilder("SET search_path TO ");
        String sep = "";
        for (String name : schemas.split(",")) {
            name = name.trim();
            if (name.isEmpty()) {
                continue;
            }
            boolean quoted = name.length() > 1 && name.startsWith("\"") && name.endsWith("\"");
            sql.append(sep).append(quoted ? name : quoteIdentifier(name));
            sep = ", ";
        }
        if (!sep.isEmpty()) {
            wire.simpleQuery(sql.toString());
        }
    }

    // --- unsupported surface ---

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        ensureOpen();
        NusaCallableStatement.Call call = NusaCallableStatement.translate(sql, callMode);
        return new NusaCallableStatement(this, prepared(call.sql), call.hasReturn);
    }

    @Override
    public Statement createStatement(int rsType, int rsConcurrency) throws SQLException {
        NusaStatement st = (NusaStatement) createStatement();
        st.resultSetType(rsType);
        return st;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int rsType, int rsConcurrency) throws SQLException {
        NusaPreparedStatement ps = (NusaPreparedStatement) prepareStatement(sql);
        ps.resultSetType(rsType);
        return ps;
    }

    @Override
    public CallableStatement prepareCall(String sql, int rsType, int rsConcurrency) throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public Statement createStatement(int rsType, int rsConcurrency, int rsHoldability) throws SQLException {
        return createStatement(rsType, rsConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int rsType, int rsConcurrency, int rsHoldability)
            throws SQLException {
        return prepareStatement(sql, rsType, rsConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int rsType, int rsConcurrency, int rsHoldability)
            throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        ensureOpen();
        if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
            String augmented = NusaStatement.withReturning(prepared(sql), "*");
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
            String augmented = NusaStatement.withReturning(prepared(sql), "*");
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
            String augmented = NusaStatement.withReturning(prepared(sql), String.join(", ", columnNames));
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
     * quotes) is emitted safely. The server folds a quoted identifier to its literal value, so the
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

    // --- asynchronous notifications (LISTEN/NOTIFY), reached via unwrap(NusaListener.class) ---

    /** Quotes a string as a SQL literal (`'…'`, doubling embedded `'`), used for a NOTIFY payload. */
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

    /** An empty in-memory CLOB; the server stores its value in a {@code text} column. */
    @Override
    public Clob createClob() throws SQLException {
        ensureOpen();
        return new NusaClob("");
    }

    /** An empty in-memory BLOB; the server stores its value in a {@code bytea} column. */
    @Override
    public Blob createBlob() throws SQLException {
        ensureOpen();
        return new NusaBlob(null);
    }

    @Override
    public NClob createNClob() throws SQLException {
        ensureOpen();
        return new NusaClob("");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw unsupported();
    }

    /** The one client info property the driver knows. */
    static final String APPLICATION_NAME = "ApplicationName";

    void applicationName(String name) {
        applicationName = name;
    }

    private void clientInfoOpen(Map<String, java.sql.ClientInfoStatus> failed) throws SQLClientInfoException {
        if (isClosed()) {
            throw new SQLClientInfoException("nusadb: connection is closed", "08003", 0, failed);
        }
    }

    /** Only {@code ApplicationName} is known; any other name is refused. */
    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        clientInfoOpen(Map.of(name == null ? "" : name, java.sql.ClientInfoStatus.REASON_UNKNOWN));
        if (!APPLICATION_NAME.equals(name)) {
            throw new SQLClientInfoException("nusadb: unknown client info property: " + name, "HY000",
                    Map.of(name == null ? "" : name, java.sql.ClientInfoStatus.REASON_UNKNOWN_PROPERTY));
        }
        applicationName = value;
    }

    /**
     * Replaces the client info with {@code properties}: {@code ApplicationName} is cleared when the
     * properties leave it out. Nothing is changed when one of them is unknown.
     */
    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        Map<String, java.sql.ClientInfoStatus> unknown = new java.util.HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (!APPLICATION_NAME.equals(name)) {
                unknown.put(name, java.sql.ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
            }
        }
        clientInfoOpen(unknown);
        if (!unknown.isEmpty()) {
            throw new SQLClientInfoException("nusadb: unknown client info properties: " + unknown.keySet(),
                    "HY000", unknown);
        }
        applicationName = properties.getProperty(APPLICATION_NAME);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        ensureOpen();
        return APPLICATION_NAME.equals(name) ? applicationName : null;
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        ensureOpen();
        Properties p = new Properties();
        String name = applicationName;
        if (name != null) {
            p.setProperty(APPLICATION_NAME, name);
        }
        return p;
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return new NusaArray(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw unsupported();
    }

    /**
     * Pools abort a connection that is stuck, so this must not wait on the server: the socket is
     * dropped at once (a statement blocked on it fails with an I/O error) and the server rolls back
     * whatever transaction was open when it sees the connection go.
     */
    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw new SQLException("nusadb: abort needs an executor", "HY009");
        }
        if (closed) {
            return;
        }
        closed = true;
        executor.execute(wire::forceClose);
    }

    /**
     * How long to wait for any reply before giving up. When it expires the connection is closed,
     * since the late reply would leave the stream out of step; {@code 0} waits forever.
     */
    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        ensureOpen();
        if (milliseconds < 0) {
            throw new SQLException("nusadb: network timeout must be >= 0", "22023");
        }
        wire.setReadTimeout(milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        ensureOpen();
        return wire.readTimeout();
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
