package com.nusadb.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC {@link java.sql.PreparedStatement} over the extended-query protocol.
 *
 * <p>Each setter records the parameter's type tag next to its text value, and the statement is
 * prepared on the server with those tags declared. Without them the server infers a type from the
 * text, so {@code setString("00123")} would arrive as an integer and a {@code BigDecimal} with more
 * digits than a double holds would lose them. Parse needs the tags before Bind, so the statement is
 * prepared once per distinct combination of types it is executed with (usually one).
 */
class NusaPreparedStatement extends AbstractPreparedStatement {
    /**
     * The statement prepared without declared types (at construction, to surface a parse error).
     * With the connection's statement cache it is looked up again at each use instead, since the
     * cache may close it in between.
     */
    private final String name;
    /**
     * Server statements by declared types, for the combinations seen so far, when the connection
     * keeps no statement cache; this statement then closes them itself.
     */
    private final Map<String, String> typed = new HashMap<>();
    private static final byte[] UNTYPED = new byte[0];
    /** The statement text as prepared ({@code $n} placeholders), kept for batch rewriting. */
    private final String sql;
    private final List<byte[]> params = new ArrayList<>();
    private final List<Byte> types = new ArrayList<>();
    private final List<List<byte[]>> paramBatch = new ArrayList<>();
    private final List<byte[]> typeBatch = new ArrayList<>();
    // When true the statement was prepared with a RETURNING clause (RETURN_GENERATED_KEYS), so its
    // result rows are captured as generated keys rather than reported as a normal ResultSet.
    private final boolean captureKeys;

    NusaPreparedStatement(NusaConnection connection, String sql) throws SQLException {
        this(connection, sql, false);
    }

    NusaPreparedStatement(NusaConnection connection, String sql, boolean captureKeys)
            throws SQLException {
        super(connection);
        this.sql = sql;
        this.name = connection.statements.statement(sql, UNTYPED);
        this.captureKeys = captureKeys;
    }

    /** The query text, for an updatable result set. */
    @Override
    String querySql() {
        return sql;
    }

    /** Binds {@code text} with no declared type: the server types it from where it is used. */
    void setUntyped(int parameterIndex, String text) throws SQLException {
        set(parameterIndex, text == null ? null : bytes(text));
    }

    private void set(int parameterIndex, byte[] value) throws SQLException {
        set(parameterIndex, value, Protocol.TAG_INFER);
    }

    /**
     * The statement's parameter number for a JDBC parameter index; 0 for an index the statement has
     * no parameter for (the return value of a {@code {? = call ..}}). The same here; shifted by
     * {@link NusaCallableStatement}.
     */
    int statementIndex(int jdbcIndex) throws SQLException {
        return jdbcIndex;
    }

    private void set(int jdbcIndex, byte[] value, byte tag) throws SQLException {
        if (jdbcIndex < 1) {
            throw new SQLException("nusadb: parameter index must be >= 1", "07009");
        }
        int parameterIndex = statementIndex(jdbcIndex);
        if (parameterIndex == 0) {
            return;
        }
        while (params.size() < parameterIndex) {
            params.add(null);
            types.add(Protocol.TAG_INFER);
        }
        params.set(parameterIndex - 1, value);
        // A NULL is NULL whatever its declared type; leaving it untyped keeps one server statement.
        types.set(parameterIndex - 1, value == null ? Protocol.TAG_INFER : tag);
    }

    private byte textTag() {
        return connection.untypedStrings ? Protocol.TAG_INFER : Protocol.TAG_TEXT;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void clearParameters() {
        params.clear();
        types.clear();
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        set(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        set(parameterIndex, bytes(x ? "true" : "false"), Protocol.TAG_BOOL);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        set(parameterIndex, bytes(Byte.toString(x)), Protocol.TAG_INT);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        set(parameterIndex, bytes(Short.toString(x)), Protocol.TAG_INT);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        set(parameterIndex, bytes(Integer.toString(x)), Protocol.TAG_INT);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        set(parameterIndex, bytes(Long.toString(x)), Protocol.TAG_INT);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        set(parameterIndex, bytes(Float.toString(x)), Protocol.TAG_FLOAT);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        set(parameterIndex, bytes(Double.toString(x)), Protocol.TAG_FLOAT);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        // toPlainString: BigDecimal.toString switches to exponent form (1E+3) for some scales.
        set(parameterIndex, x == null ? null : bytes(x.toPlainString()), Protocol.TAG_NUMERIC);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        set(parameterIndex, x == null ? null : bytes(x), textTag());
    }

    /** An XML value is sent as its text (the server stores XML in a text column). */
    @Override
    public void setSQLXML(int parameterIndex, java.sql.SQLXML xmlObject) throws SQLException {
        setString(parameterIndex, xmlObject == null ? null
                : xmlObject instanceof NusaSQLXML x ? x.text() : xmlObject.getString());
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        // Send as the BYTEA text form `\x<hex>` so the server coerces it into a BYTEA column.
        set(parameterIndex, x == null ? null : bytes(hexLiteral(x)), Protocol.TAG_BYTES);
    }

    /** The BYTEA text literal {@code \x<hex>} for a byte array (lowercase hex). */
    private static String hexLiteral(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
        sb.append("\\x");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        setDate(parameterIndex, x, null);
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        set(parameterIndex, x == null ? null : bytes(Temporals.format(x, cal)));
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        setTime(parameterIndex, x, null);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        set(parameterIndex, x == null ? null : bytes(Temporals.format(x, cal)));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        setTimestamp(parameterIndex, x, null);
    }

    /**
     * With a calendar the value is sent with that zone's offset, so a TIMESTAMPTZ column stores the
     * exact instant and a TIMESTAMP column stores the wall-clock time in that zone.
     */
    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        set(parameterIndex, x == null ? null : bytes(Temporals.format(x, cal)));
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        set(parameterIndex, null);
    }

    @Override
    public void setArray(int parameterIndex, java.sql.Array x) throws SQLException {
        // Bind a java.sql.Array (from Connection.createArrayOf) as the SQL array literal {a,b}.
        set(parameterIndex, x == null ? null : bytes(arrayLiteral(x)));
    }

    // --- LOBs and streams: the server has no large-object store, so each is read in full and sent
    // as the text or bytea value it stands for ---

    @Override
    public void setClob(int parameterIndex, java.sql.Clob x) throws SQLException {
        setString(parameterIndex, x == null ? null : NusaClob.read(x));
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        setString(parameterIndex, read(reader, -1));
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setString(parameterIndex, read(reader, length));
    }

    @Override
    public void setNClob(int parameterIndex, java.sql.NClob value) throws SQLException {
        setClob(parameterIndex, value);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        setClob(parameterIndex, reader);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setClob(parameterIndex, reader, length);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        setClob(parameterIndex, reader);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        setClob(parameterIndex, reader, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        setClob(parameterIndex, reader, length);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        setClob(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        setClob(parameterIndex, value, length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        byte[] b = read(x, -1);
        setString(parameterIndex, b == null ? null : new String(b, StandardCharsets.US_ASCII));
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setAsciiStream(parameterIndex, x, (long) length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        byte[] b = read(x, length);
        setString(parameterIndex, b == null ? null : new String(b, StandardCharsets.US_ASCII));
    }

    @Override
    public void setBlob(int parameterIndex, java.sql.Blob x) throws SQLException {
        setBytes(parameterIndex, x == null ? null : NusaBlob.read(x));
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        setBytes(parameterIndex, read(inputStream, -1));
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        setBytes(parameterIndex, read(inputStream, length));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        setBlob(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setBlob(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setBlob(parameterIndex, x, length);
    }

    /** Up to {@code length} characters of the reader (all of it when negative), or null for null. */
    private static String read(Reader reader, long length) throws SQLException {
        if (reader == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(length > 0 && length < 1 << 20 ? (int) length : 8192);
        char[] buf = new char[8192];
        try {
            long left = length < 0 ? Long.MAX_VALUE : length;
            int n;
            while (left > 0 && (n = reader.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                sb.append(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            throw new SQLException("nusadb: reading the parameter stream failed: " + e.getMessage(), "HY000", e);
        }
        return sb.toString();
    }

    /** Up to {@code length} bytes of the stream (all of it when negative), or null for null. */
    private static byte[] read(InputStream in, long length) throws SQLException {
        if (in == null) {
            return null;
        }
        try {
            if (length < 0) {
                return in.readAllBytes();
            }
            if (length > Integer.MAX_VALUE) {
                throw new SQLException("nusadb: a stream over 2^31 bytes cannot be sent", "22001");
            }
            return in.readNBytes((int) length);
        } catch (IOException e) {
            throw new SQLException("nusadb: reading the parameter stream failed: " + e.getMessage(), "HY000", e);
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        if (x == null) {
            set(parameterIndex, null);
        } else if (x instanceof byte[]) {
            setBytes(parameterIndex, (byte[]) x);
        } else if (x instanceof java.sql.Clob) {
            setClob(parameterIndex, (java.sql.Clob) x);
        } else if (x instanceof java.sql.Blob) {
            setBlob(parameterIndex, (java.sql.Blob) x);
        } else if (x instanceof java.sql.SQLXML) {
            setSQLXML(parameterIndex, (java.sql.SQLXML) x);
        } else if (x instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) x);
        } else if (x instanceof java.sql.Array) {
            setArray(parameterIndex, (java.sql.Array) x);
        } else if (x instanceof java.util.Collection || x.getClass().isArray()) {
            // A Java array or Collection binds as a SQL array literal, matching JDBC's array path.
            set(parameterIndex, bytes(arrayLiteral(x)));
        } else if (x instanceof BigDecimal) {
            setBigDecimal(parameterIndex, (BigDecimal) x);
        } else if (x instanceof java.math.BigInteger) {
            set(parameterIndex, bytes(x.toString()), Protocol.TAG_NUMERIC);
        } else if (x instanceof Integer || x instanceof Long || x instanceof Short || x instanceof Byte) {
            set(parameterIndex, bytes(x.toString()), Protocol.TAG_INT);
        } else if (x instanceof Double || x instanceof Float) {
            set(parameterIndex, bytes(x.toString()), Protocol.TAG_FLOAT);
        } else if (x instanceof String || x instanceof Character) {
            set(parameterIndex, bytes(x.toString()), textTag());
        } else if (x instanceof java.util.UUID) {
            set(parameterIndex, bytes(x.toString()), Protocol.TAG_UUID);
        } else if (x instanceof Enum<?>) {
            set(parameterIndex, bytes(((Enum<?>) x).name()), textTag());
        } else {
            // java.time and legacy date types get the server's text form (a ZonedDateTime's
            // "[Region/City]" suffix, for one, would not parse); everything else its toString.
            String temporal = Temporals.parameter(x);
            set(parameterIndex, bytes(temporal != null ? temporal : x.toString()));
        }
    }

    /** Render a java.sql.Array / Java array / Collection as a SQL array literal {@code {a,b}}. */
    static String arrayLiteral(Object value) throws SQLException {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Object el : toElements(value)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(arrayElement(el));
        }
        sb.append('}');
        return sb.toString();
    }

    private static java.util.List<Object> toElements(Object value) throws SQLException {
        Object v = value instanceof java.sql.Array ? ((java.sql.Array) value).getArray() : value;
        java.util.List<Object> out = new ArrayList<>();
        if (v instanceof java.util.Collection) {
            out.addAll((java.util.Collection<?>) v);
        } else if (v != null && v.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) {
                out.add(java.lang.reflect.Array.get(v, i));
            }
        } else {
            out.add(v);
        }
        return out;
    }

    private static String arrayElement(Object el) throws SQLException {
        if (el == null) {
            return "NULL";
        }
        if (el instanceof byte[]) {
            return quoteArrayToken(hexLiteral((byte[]) el));
        }
        if (el instanceof java.sql.Array || el instanceof java.util.Collection
                || el.getClass().isArray()) {
            return arrayLiteral(el);
        }
        if (el instanceof Boolean) {
            return ((Boolean) el) ? "true" : "false";
        }
        return quoteArrayToken(el.toString());
    }

    private static String quoteArrayToken(String s) {
        boolean need = s.isEmpty() || s.equalsIgnoreCase("NULL");
        if (!need) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '{' || c == '}' || c == ',' || c == '"' || c == '\\'
                        || Character.isWhitespace(c)) {
                    need = true;
                    break;
                }
            }
        }
        if (!need) {
            return s;
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // An explicit JDBC target type decides the declared type for the scalar families; anything else
    // is declared from the value as in setObject(int, Object).

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x);
        byte tag = tagFor(targetSqlType);
        int index = statementIndex(parameterIndex);
        if (index > 0 && x != null && tag != Protocol.TAG_INFER && !(x instanceof byte[]) && !(x instanceof java.sql.Array)) {
            types.set(index - 1, tag == Protocol.TAG_TEXT ? textTag() : tag);
        }
    }

    /** The declared type for a {@link java.sql.Types} code, or {@link Protocol#TAG_INFER}. */
    static byte tagFor(int sqlType) {
        switch (sqlType) {
            case java.sql.Types.BOOLEAN:
            case java.sql.Types.BIT:
                return Protocol.TAG_BOOL;
            case java.sql.Types.TINYINT:
            case java.sql.Types.SMALLINT:
            case java.sql.Types.INTEGER:
            case java.sql.Types.BIGINT:
                return Protocol.TAG_INT;
            case java.sql.Types.REAL:
            case java.sql.Types.FLOAT:
            case java.sql.Types.DOUBLE:
                return Protocol.TAG_FLOAT;
            case java.sql.Types.NUMERIC:
            case java.sql.Types.DECIMAL:
                return Protocol.TAG_NUMERIC;
            case java.sql.Types.CHAR:
            case java.sql.Types.VARCHAR:
            case java.sql.Types.LONGVARCHAR:
            case java.sql.Types.NCHAR:
            case java.sql.Types.NVARCHAR:
            case java.sql.Types.LONGNVARCHAR:
            case java.sql.Types.CLOB:
            case java.sql.Types.NCLOB:
                return Protocol.TAG_TEXT;
            case java.sql.Types.BINARY:
            case java.sql.Types.VARBINARY:
            case java.sql.Types.LONGVARBINARY:
            case java.sql.Types.BLOB:
                return Protocol.TAG_BYTES;
            default:
                return Protocol.TAG_INFER;
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
            throws SQLException {
        if (x instanceof BigDecimal && (targetSqlType == java.sql.Types.NUMERIC
                || targetSqlType == java.sql.Types.DECIMAL)) {
            setBigDecimal(parameterIndex, ((BigDecimal) x).setScale(scaleOrLength, java.math.RoundingMode.HALF_UP));
            return;
        }
        setObject(parameterIndex, x, targetSqlType);
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
        Integer vendor = targetSqlType == null ? null : targetSqlType.getVendorTypeNumber();
        setObject(parameterIndex, x, vendor == null ? java.sql.Types.OTHER : vendor);
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength)
            throws SQLException {
        Integer vendor = targetSqlType == null ? null : targetSqlType.getVendorTypeNumber();
        setObject(parameterIndex, x, vendor == null ? java.sql.Types.OTHER : vendor, scaleOrLength);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        super.close();
        if (!connection.isClosed() && !connection.statements.keeps()) {
            List<String> names = new ArrayList<>(typed.values());
            names.add(name);
            for (String n : names) {
                try {
                    connection.wire.closeStatement(n);
                } catch (SQLException ignored) {
                    // Best effort: an error here (an aborted transaction, a dropped socket) must not
                    // mask the caller's own; the statement goes away with the session at the latest.
                }
            }
        }
        typed.clear();
    }

    private List<byte[]> snapshot() {
        return new ArrayList<>(params);
    }

    private byte[] typeSnapshot() {
        byte[] t = new byte[types.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = types.get(i);
        }
        return t;
    }

    /** The server statement declaring {@code tags}: the untyped one when nothing is declared. */
    private String statementFor(byte[] tags) throws SQLException {
        int len = tags.length;
        while (len > 0 && tags[len - 1] == Protocol.TAG_INFER) {
            len--;
        }
        StatementCache cache = connection.statements;
        if (len == 0) {
            return cache.keeps() ? cache.statement(sql, UNTYPED) : name;
        }
        byte[] declared = java.util.Arrays.copyOf(tags, len);
        if (cache.keeps()) {
            return cache.statement(sql, declared);
        }
        String key = java.util.Arrays.toString(declared);
        String stmt = typed.get(key);
        if (stmt == null) {
            stmt = connection.wire.prepareStatement(sql, declared);
            typed.put(key, stmt);
        }
        return stmt;
    }

    private Wire.Result execCurrent() throws SQLException {
        String stmt = statementFor(typeSnapshot());
        List<byte[]> values = snapshot();
        return run(() -> connection.execPrepared(stmt, values));
    }

    /** Runs the statement with its rows read in chunks; false when it is not to be streamed. */
    private boolean streamCurrent() throws SQLException {
        if (captureKeys || !streams()) {
            return false;
        }
        String stmt = statementFor(typeSnapshot());
        List<byte[]> values = snapshot();
        applyStream(run(() -> connection.streamPrepared(stmt, values)));
        return true;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        ensureOpen();
        if (!streamCurrent()) {
            apply(execCurrent());
        }
        ResultSet rs = getResultSet();
        return rs != null ? rs : new NusaResultSet(this, new Wire.Result(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null));
    }

    @Override
    public int executeUpdate() throws SQLException {
        return narrow(executeLargeUpdate());
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        ensureOpen();
        Wire.Result r = execCurrent();
        if (captureKeys && !r.columns.isEmpty()) {
            applyGeneratedKeys(r);
            return getLargeUpdateCount();
        }
        apply(r);
        return Math.max(getLargeUpdateCount(), 0);
    }

    @Override
    public boolean execute() throws SQLException {
        ensureOpen();
        if (streamCurrent()) {
            return getResultSet() != null;
        }
        Wire.Result r = execCurrent();
        if (captureKeys && !r.columns.isEmpty()) {
            applyGeneratedKeys(r);
            return false; // generated keys are read via getGeneratedKeys(), not getResultSet()
        }
        apply(r);
        return getResultSet() != null;
    }

    @Override
    public void addBatch() throws SQLException {
        ensureOpen();
        paramBatch.add(snapshot());
        typeBatch.add(typeSnapshot());
    }

    @Override
    public void clearBatch() {
        paramBatch.clear();
        typeBatch.clear();
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        ensureOpen();
        if (connection.rewriteBatchedInserts && !captureKeys && paramBatch.size() > 1) {
            InsertRewriter rewriter = InsertRewriter.of(sql);
            if (rewriter != null && sameWidth(rewriter.paramsPerRow())) {
                return executeRewritten(rewriter);
            }
        }
        if (!captureKeys) {
            return executePipelined();
        }
        long[] counts = new long[paramBatch.size()];
        for (int i = 0; i < paramBatch.size(); i++) {
            Wire.Result r = connection.execPrepared(statementFor(typeBatch.get(i)), paramBatch.get(i));
            if (!r.columns.isEmpty()) {
                // RETURNING yields one row per affected row; keep the last batch entry's keys.
                applyGeneratedKeys(r);
                counts[i] = r.rows.size();
            } else {
                counts[i] = Math.max(affected(r.tag), 0);
            }
        }
        paramBatch.clear();
        typeBatch.clear();
        return counts;
    }

    /**
     * The batch in a few round trips instead of one per row: every row's Bind and Execute are sent
     * before the server is waited for (see {@link Wire#executeBatch}). Each row reports its own
     * update count.
     */
    private long[] executePipelined() throws SQLException {
        List<String> statements = new ArrayList<>(paramBatch.size());
        for (byte[] tags : typeBatch) {
            statements.add(statementFor(tags)); // prepares a statement per parameter-type signature
        }
        List<List<byte[]>> rows = new ArrayList<>(paramBatch);
        try {
            return run(() -> connection.execBatch(statements, rows));
        } finally {
            paramBatch.clear();
            typeBatch.clear();
        }
    }

    private boolean sameWidth(int params) {
        for (List<byte[]> entry : paramBatch) {
            if (entry.size() != params) {
                return false;
            }
        }
        return true;
    }

    /**
     * The batch as multi-row INSERTs, as many rows per statement as the parameter limit allows.
     * Per-row counts are unknown then, so every entry reports {@link java.sql.Statement#SUCCESS_NO_INFO}.
     */
    private long[] executeRewritten(InsertRewriter rewriter) throws SQLException {
        int total = paramBatch.size();
        int per = rewriter.rowsPerStatement();
        for (int start = 0; start < total; start += per) {
            int rows = Math.min(per, total - start);
            int width = rewriter.paramsPerRow();
            List<byte[]> flat = new ArrayList<>(rows * width);
            byte[] row = rowTypes(start, rows, width);
            byte[] tags = new byte[rows * width];
            for (int i = start; i < start + rows; i++) {
                flat.addAll(paramBatch.get(i));
                System.arraycopy(row, 0, tags, (i - start) * width, width);
            }
            String chunk = connection.wire.prepareStatement(rewriter.sql(rows), tags);
            try {
                run(() -> connection.execPrepared(chunk, flat));
            } finally {
                try {
                    connection.wire.closeStatement(chunk);
                } catch (SQLException ignored) {
                    // the statement dies with the session at the latest
                }
            }
        }
        paramBatch.clear();
        typeBatch.clear();
        long[] counts = new long[total];
        java.util.Arrays.fill(counts, java.sql.Statement.SUCCESS_NO_INFO);
        return counts;
    }

    /**
     * One declared type per column for a chunk of batch rows. A NULL carries no type, so a column
     * takes the type of its non-null values; rows that disagree leave the column to the server.
     */
    private byte[] rowTypes(int start, int rows, int width) {
        byte[] out = new byte[width];
        boolean[] conflict = new boolean[width];
        for (int i = start; i < start + rows; i++) {
            byte[] t = typeBatch.get(i);
            for (int c = 0; c < width && c < t.length; c++) {
                if (t[c] == Protocol.TAG_INFER) {
                    continue;
                }
                if (out[c] == Protocol.TAG_INFER) {
                    out[c] = t[c];
                } else if (out[c] != t[c]) {
                    conflict[c] = true;
                }
            }
        }
        for (int c = 0; c < width; c++) {
            if (conflict[c]) {
                out[c] = Protocol.TAG_INFER;
            }
        }
        return out;
    }

    // A PreparedStatement carries its SQL from construction, so the String-taking batch/execute
    // methods are not allowed on it (JDBC spec).

    @Override
    public void addBatch(String sql) throws SQLException {
        throw new SQLException("nusadb: addBatch(String) is not allowed on a PreparedStatement", "0A000");
    }


    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw new SQLException("nusadb: executeQuery(String) is not allowed on a PreparedStatement", "0A000");
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw new SQLException("nusadb: executeUpdate(String) is not allowed on a PreparedStatement", "0A000");
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw new SQLException("nusadb: executeLargeUpdate(String) is not allowed on a PreparedStatement", "0A000");
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw new SQLException("nusadb: execute(String) is not allowed on a PreparedStatement", "0A000");
    }
}
