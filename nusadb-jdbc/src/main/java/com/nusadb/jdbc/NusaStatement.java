package com.nusadb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** JDBC {@link Statement} over the simple-query protocol. */
class NusaStatement implements Statement {
    final NusaConnection connection;
    private final List<String> batch = new ArrayList<>();
    /** JDBC escape syntax ({fn ..}, {d ..}) is translated unless turned off for this statement. */
    private boolean escapeProcessing = true;
    /** {@link ResultSet#TYPE_FORWARD_ONLY} or {@link ResultSet#TYPE_SCROLL_INSENSITIVE}. */
    private int resultSetType = ResultSet.TYPE_FORWARD_ONLY;
    private ResultSet current;
    private long updateCount = -1;
    private int fetchSize;
    private ResultSet generatedKeys;
    private int queryTimeoutSeconds;
    private long maxRows;
    /** True while a statement of this object is on the wire, so cancel() knows there is work to stop. */
    private volatile boolean executing;
    boolean closed;

    /** One daemon thread fires every query timeout of the JVM; it only ever sends cancel requests. */
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "nusadb-query-timeout");
        t.setDaemon(true);
        return t;
    });

    static {
        TIMEOUTS.setRemoveOnCancelPolicy(true);
    }

    /** One exchange with the server: a statement, or a pipelined batch. */
    interface WireCall<T> {
        T call() throws SQLException;
    }

    /**
     * Run {@code call}, cancelling it server-side if it is still running after the query timeout.
     * A statement stopped that way surfaces as {@link SQLTimeoutException}; the connection stays
     * usable, exactly as after any other statement error.
     */
    <T> T run(WireCall<T> call) throws SQLException {
        int timeout = queryTimeoutSeconds;
        AtomicBoolean fired = new AtomicBoolean();
        ScheduledFuture<?> timer = null;
        if (timeout > 0) {
            Wire wire = connection.wire;
            timer = TIMEOUTS.schedule(() -> {
                fired.set(true);
                try {
                    wire.cancel();
                } catch (SQLException ignored) {
                    // the statement simply runs on; there is no caller to report to here
                }
            }, timeout, TimeUnit.SECONDS);
        }
        executing = true;
        try {
            return call.call();
        } catch (SQLException e) {
            if (fired.get() && "57014".equals(e.getSQLState())) {
                throw new SQLTimeoutException(
                        "nusadb: statement cancelled after the " + timeout + " s query timeout", "57014", e);
            }
            throw e;
        } finally {
            executing = false;
            if (timer != null) {
                timer.cancel(false);
            }
        }
    }

    NusaStatement(NusaConnection connection) {
        this.connection = connection;
        this.fetchSize = connection.defaultFetchSize;
    }

    static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("nusadb: feature not supported");
    }

    void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("nusadb: statement is closed", "24000");
        }
    }

    /** The row count at the end of a command tag ({@code INSERT 0 5}, {@code UPDATE 3}); 0 when it has none. */
    static long affected(String tag) {
        if (tag == null) {
            return 0;
        }
        int sp = tag.lastIndexOf(' ');
        if (sp < 0) {
            return 0;
        }
        try {
            return Long.parseLong(tag.substring(sp + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    void apply(Wire.Result result) {
        closeCurrent();
        generatedKeys = null;
        if (!result.columns.isEmpty()) {
            current = new NusaResultSet(this, result, narrow(maxRows));
            updateCount = -1;
        } else {
            current = null;
            updateCount = affected(result.tag);
        }
    }

    /** A new execution closes the result set of the one before, freeing an open portal. */
    private void closeCurrent() {
        if (current != null) {
            try {
                current.close();
            } catch (SQLException ignored) {
                // a cursor's close swallows its own failures; nothing else can fail here
            }
            current = null;
        }
    }

    /**
     * Whether a query's rows are read in chunks of {@link #getFetchSize()} as the result set is
     * read, instead of all at once: whenever a fetch size is set.
     */
    boolean streams() throws SQLException {
        return fetchSize > 0 && resultSetType == ResultSet.TYPE_FORWARD_ONLY; // scrolling needs every row
    }

    /** A query whose rows are read lazily: its result set, or the update count of a non-query. */
    void applyStream(Wire.RowStream stream) throws SQLException {
        if (stream.columns.isEmpty()) {
            apply(new Wire.Result(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), stream.tag()));
            return;
        }
        closeCurrent();
        generatedKeys = null;
        current = new NusaResultSet(this, stream, new NusaCursor(stream, fetchSize, narrow(maxRows)));
        updateCount = -1;
    }

    /** Runs a query whose rows are read in chunks; false when it is not to be streamed. */
    private boolean streamQuery(String sql) throws SQLException {
        if (!streams()) {
            return false;
        }
        closeCurrent();
        applyStream(run(() -> connection.streamSimple(sql)));
        return true;
    }

    /**
     * Record a RETURNING result as this statement's generated keys: the returned rows become the
     * {@link #getGeneratedKeys()} result and their count is the update count (one RETURNING row per
     * affected row). Used by the {@code RETURN_GENERATED_KEYS} execute paths.
     */
    void applyGeneratedKeys(Wire.Result result) {
        generatedKeys = new NusaResultSet(this, result);
        current = null;
        updateCount = result.rows.size();
    }

    private ResultSet emptyResultSet() throws SQLException {
        return new NusaResultSet(this, new Wire.Result(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null));
    }

    /**
     * Append a {@code RETURNING} clause to an INSERT so {@link #getGeneratedKeys()} can read the new
     * row(s). Returns {@code null} when the statement is not an INSERT or already carries its own
     * RETURNING, in which case the caller runs it unchanged (no key capture / capture as-is).
     */
    static String withReturning(String sql, String columns) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("insert")) {
            return null;
        }
        if (hasReturning(trimmed)) {
            return null;
        }
        return trimmed + " RETURNING " + columns;
    }

    /**
     * True if {@code sql} already carries a RETURNING keyword in executable position, i.e. outside
     * string literals, quoted identifiers, and comments, so a value like {@code 'returning x'} does
     * not fool the check. Mirrors the literal/comment skipping in {@link NusaConnection#positionalize}.
     */
    static boolean hasReturning(String sql) {
        int len = sql.length();
        StringBuilder code = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char ch = sql.charAt(i);
            if (ch == '\'' || ch == '"') {
                // String literal or quoted identifier: skip it, honouring doubled-quote escapes.
                for (i++; i < len; i++) {
                    char c = sql.charAt(i);
                    if (c == ch) {
                        if (i + 1 < len && sql.charAt(i + 1) == ch) {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (ch == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                // Line comment: skip through the end of line.
                for (; i < len && sql.charAt(i) != '\n'; i++) {
                    // skip
                }
            } else if (ch == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                // Block comment: skip through the closing '*/'.
                for (i += 2; i < len; i++) {
                    if (sql.charAt(i) == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                        i++;
                        break;
                    }
                }
            } else {
                code.append(ch);
            }
        }
        return code.toString().toLowerCase(Locale.ROOT).matches("(?s).*\\breturning\\b.*");
    }

    /** Run a simple-query update, capturing generated keys when the INSERT can carry RETURNING. */
    private long executeUpdateReturning(String written, String columns) throws SQLException {
        ensureOpen();
        String sql = escaped(written);
        String augmented = withReturning(sql, columns);
        // Run the augmented INSERT, or the original when it is not an INSERT or already carries its
        // own RETURNING. Either way a result with rows is the generated keys; a bare command tag is a
        // plain update with none.
        Wire.Result result = run(() -> connection.execSimple(augmented != null ? augmented : sql));
        if (!result.columns.isEmpty()) {
            applyGeneratedKeys(result);
            return updateCount;
        }
        apply(result);
        return Math.max(updateCount, 0);
    }

    @Override
    public ResultSet executeQuery(String written) throws SQLException {
        ensureOpen();
        String sql = escaped(written);
        if (!streamQuery(sql)) {
            apply(run(() -> connection.execSimple(sql)));
        }
        return current != null ? current : new NusaResultSet(this, new Wire.Result(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null));
    }

    /**
     * A count as an {@code int}: one past {@link Integer#MAX_VALUE} reads as {@code Integer.MAX_VALUE}
     * (the {@code getLarge..} and {@code executeLarge..} methods give it whole).
     */
    static int narrow(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    static int[] narrow(long[] counts) {
        int[] out = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            out[i] = narrow(counts[i]);
        }
        return out;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return narrow(executeLargeUpdate(sql));
    }

    @Override
    public long executeLargeUpdate(String written) throws SQLException {
        ensureOpen();
        String sql = escaped(written);
        apply(run(() -> connection.execSimple(sql)));
        return Math.max(updateCount, 0);
    }

    @Override
    public boolean execute(String written) throws SQLException {
        ensureOpen();
        String sql = escaped(written);
        if (!streamQuery(sql)) {
            apply(run(() -> connection.execSimple(sql)));
        }
        return current != null;
    }

    @Override
    public ResultSet getResultSet() {
        return current;
    }

    @Override
    public int getUpdateCount() {
        return narrow(updateCount);
    }

    @Override
    public long getLargeUpdateCount() {
        return updateCount;
    }

    @Override
    public boolean getMoreResults() {
        current = null;
        updateCount = -1;
        return false;
    }

    @Override
    public boolean getMoreResults(int c) {
        return getMoreResults();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        batch.add(escaped(sql));
    }

    /** The SQL as sent: escapes translated, unless setEscapeProcessing(false). */
    private String escaped(String sql) throws SQLException {
        return escapeProcessing ? JdbcEscapes.translate(sql) : sql;
    }

    @Override
    public void clearBatch() {
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return narrow(executeLargeBatch());
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        ensureOpen();
        // Pipelined: the queries go out before their replies are read (see Wire#simpleBatch).
        List<String> queries = new ArrayList<>(batch);
        try {
            return run(() -> connection.execSimpleBatch(queries));
        } finally {
            batch.clear();
        }
    }

    @Override
    public void close() {
        closed = true;
        closeCurrent();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void setFetchSize(int rows) {
        fetchSize = rows;
    }

    @Override
    public int getFetchSize() {
        return fetchSize;
    }

    @Override
    public void setFetchDirection(int direction) {
        // inert
    }

    @Override
    public int getFetchDirection() {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public int getResultSetConcurrency() {
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() {
        return resultSetType;
    }

    /**
     * The result set type asked for at creation. A scroll-sensitive result set is not offered:
     * like other drivers that read the rows to the client, it becomes scroll-insensitive.
     */
    void resultSetType(int type) throws SQLException {
        switch (type) {
            case ResultSet.TYPE_FORWARD_ONLY:
            case ResultSet.TYPE_SCROLL_INSENSITIVE:
                resultSetType = type;
                break;
            case ResultSet.TYPE_SCROLL_SENSITIVE:
                resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE;
                break;
            default:
                throw new SQLException("nusadb: unknown result set type " + type, "HY024");
        }
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
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
    public int getMaxRows() {
        return narrow(maxRows);
    }

    @Override
    public long getLargeMaxRows() {
        return maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        setLargeMaxRows(max);
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        if (max < 0) {
            throw new SQLException("nusadb: max rows must be >= 0", "22023");
        }
        // Applied client-side: rows past the limit are dropped from the result set.
        maxRows = max;
    }

    @Override
    public int getMaxFieldSize() {
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) {
        // inert
    }

    @Override
    public int getQueryTimeout() {
        return queryTimeoutSeconds;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        if (seconds < 0) {
            throw new SQLException("nusadb: query timeout must be >= 0", "22023");
        }
        queryTimeoutSeconds = seconds;
    }

    @Override
    public void setEscapeProcessing(boolean enable) {
        // A PreparedStatement's SQL was translated when it was prepared; this affects the SQL
        // given to execute(String) and addBatch(String).
        escapeProcessing = enable;
    }

    @Override
    public void setCursorName(String name) {
        // inert
    }

    @Override
    public void cancel() throws SQLException {
        // Called from another thread while this statement runs. Nothing running, nothing to do:
        // a cancel request sent now could not be matched to a statement anyway.
        if (executing) {
            connection.wire.cancel();
        }
    }

    @Override
    public boolean isPoolable() {
        return false;
    }

    @Override
    public void setPoolable(boolean poolable) {
        // inert
    }

    @Override
    public void closeOnCompletion() {
        // inert
    }

    @Override
    public boolean isCloseOnCompletion() {
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return generatedKeys != null ? generatedKeys : emptyResultSet();
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return narrow(executeLargeUpdate(sql, autoGeneratedKeys));
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return narrow(executeLargeUpdate(sql, columnIndexes));
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return narrow(executeLargeUpdate(sql, columnNames));
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        ensureOpen();
        if (autoGeneratedKeys == RETURN_GENERATED_KEYS) {
            return executeUpdateReturning(sql, "*");
        }
        return executeLargeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        ensureOpen();
        // Column indexes cannot be mapped to names without statement metadata, so return every
        // column (the caller reads the generated keys positionally).
        if (columnIndexes != null && columnIndexes.length > 0) {
            return executeUpdateReturning(sql, "*");
        }
        return executeLargeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        ensureOpen();
        if (columnNames != null && columnNames.length > 0) {
            return executeUpdateReturning(sql, String.join(", ", columnNames));
        }
        return executeLargeUpdate(sql);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        ensureOpen();
        if (autoGeneratedKeys == RETURN_GENERATED_KEYS) {
            executeUpdateReturning(sql, "*");
            return false; // an INSERT's first result is an update count, not a ResultSet
        }
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        ensureOpen();
        if (columnIndexes != null && columnIndexes.length > 0) {
            executeUpdateReturning(sql, "*");
            return false;
        }
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        ensureOpen();
        if (columnNames != null && columnNames.length > 0) {
            executeUpdateReturning(sql, String.join(", ", columnNames));
            return false;
        }
        return execute(sql);
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
