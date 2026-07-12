package com.nusadb.jdbc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** JDBC {@link java.sql.PreparedStatement} over the extended-query protocol. */
final class NusaPreparedStatement extends AbstractPreparedStatement {
    private final String name;
    private final List<byte[]> params = new ArrayList<>();
    private final List<List<byte[]>> paramBatch = new ArrayList<>();
    // When true the statement was prepared with a RETURNING clause (RETURN_GENERATED_KEYS), so its
    // result rows are captured as generated keys rather than reported as a normal ResultSet.
    private final boolean captureKeys;

    NusaPreparedStatement(NusaConnection connection, String sql) throws SQLException {
        this(connection, sql, false);
    }

    NusaPreparedStatement(NusaConnection connection, String sql, boolean captureKeys)
            throws SQLException {
        super(connection);
        this.name = connection.wire.prepareStatement(sql);
        this.captureKeys = captureKeys;
    }

    private void set(int parameterIndex, byte[] value) throws SQLException {
        if (parameterIndex < 1) {
            throw new SQLException("nusadb: parameter index must be >= 1", "07009");
        }
        while (params.size() < parameterIndex) {
            params.add(null);
        }
        params.set(parameterIndex - 1, value);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void clearParameters() {
        params.clear();
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        set(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        set(parameterIndex, bytes(x ? "true" : "false"));
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        set(parameterIndex, bytes(Byte.toString(x)));
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        set(parameterIndex, bytes(Short.toString(x)));
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        set(parameterIndex, bytes(Integer.toString(x)));
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        set(parameterIndex, bytes(Long.toString(x)));
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        set(parameterIndex, bytes(Float.toString(x)));
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        set(parameterIndex, bytes(Double.toString(x)));
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        set(parameterIndex, x == null ? null : bytes(x.toString()));
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        set(parameterIndex, x == null ? null : bytes(x));
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        // Send as the BYTEA text form `\x<hex>` so the server coerces it into a BYTEA column.
        set(parameterIndex, x == null ? null : bytes(hexLiteral(x)));
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
        set(parameterIndex, x == null ? null : bytes(x.toString()));
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        set(parameterIndex, x == null ? null : bytes(x.toString()));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        set(parameterIndex, x == null ? null : bytes(x.toString()));
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        if (x == null) {
            set(parameterIndex, null);
        } else if (x instanceof byte[]) {
            setBytes(parameterIndex, (byte[]) x);
        } else if (x instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) x);
        } else {
            set(parameterIndex, bytes(x.toString()));
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x);
    }

    private List<byte[]> snapshot() {
        return new ArrayList<>(params);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        ensureOpen();
        Wire.Result result = connection.execPrepared(name, snapshot());
        apply(result);
        ResultSet rs = getResultSet();
        return rs != null ? rs : new NusaResultSet(this, new Wire.Result(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null));
    }

    @Override
    public int executeUpdate() throws SQLException {
        ensureOpen();
        Wire.Result r = connection.execPrepared(name, snapshot());
        if (captureKeys && !r.columns.isEmpty()) {
            applyGeneratedKeys(r);
            return getUpdateCount();
        }
        apply(r);
        return Math.max(getUpdateCount(), 0);
    }

    @Override
    public boolean execute() throws SQLException {
        ensureOpen();
        Wire.Result r = connection.execPrepared(name, snapshot());
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
    }

    @Override
    public void clearBatch() {
        paramBatch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        ensureOpen();
        int[] counts = new int[paramBatch.size()];
        for (int i = 0; i < paramBatch.size(); i++) {
            Wire.Result r = connection.execPrepared(name, paramBatch.get(i));
            if (captureKeys && !r.columns.isEmpty()) {
                // RETURNING yields one row per affected row; keep the last batch entry's keys.
                applyGeneratedKeys(r);
                counts[i] = r.rows.size();
            } else {
                counts[i] = Math.max(affected(r.tag), 0);
            }
        }
        paramBatch.clear();
        return counts;
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
    public boolean execute(String sql) throws SQLException {
        throw new SQLException("nusadb: execute(String) is not allowed on a PreparedStatement", "0A000");
    }
}
