package com.nusadb.jdbc;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link java.sql.CallableStatement} for the server's stored functions and procedures.
 *
 * <ul>
 *   <li>{@code {? = call f(?, ?)}} runs {@code SELECT f(?, ?)}: parameter 1 is the return value, an
 *       OUT parameter, and the function's arguments are parameters 2 and up.</li>
 *   <li>{@code {call f(?, ?)}} runs {@code SELECT f(?, ?) AS result}, a result set, or with
 *       {@code escapeSyntaxCallMode=callIfNoReturn} (or {@code call}) {@code CALL f(?, ?)}, for a
 *       procedure.</li>
 *   <li>Any other SQL ({@code CALL p(?)}, {@code SELECT f(?)}) runs as written.</li>
 * </ul>
 *
 * When OUT parameters are registered, the first row of the result supplies them in order (the
 * return value first) and no result set is reported. Parameters are addressed by index; the
 * server has no parameter metadata to resolve names with.
 */
final class NusaCallableStatement extends AbstractCallableStatement {

    private static final Pattern CALL = Pattern.compile(
            "\\s*\\{\\s*(\\?\\s*=\\s*)?call\\s+([^\\s(}]+)\\s*(?:\\((.*)\\))?\\s*}\\s*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** The SQL a {@code prepareCall} string runs, and whether its parameter 1 is a return value. */
    static final class Call {
        final String sql;
        final boolean hasReturn;

        Call(String sql, boolean hasReturn) {
            this.sql = sql;
            this.hasReturn = hasReturn;
        }
    }

    static Call translate(String sql, NusaConnection.CallMode mode) {
        Matcher m = CALL.matcher(sql);
        if (!m.matches()) {
            return new Call(sql, false);
        }
        boolean hasReturn = m.group(1) != null;
        String routine = m.group(2) + "(" + (m.group(3) == null ? "" : m.group(3).trim()) + ")";
        if (hasReturn) {
            return new Call("SELECT " + routine, true);
        }
        return new Call(mode == NusaConnection.CallMode.SELECT ? "SELECT " + routine + " AS result" : "CALL " + routine, false);
    }

    private final boolean hasReturn;
    /** Registered OUT parameters: JDBC index to {@link Types} code, in index order. */
    private final TreeMap<Integer, Integer> outTypes = new TreeMap<>();
    /** The row the OUT parameters were read from, positioned on it; null before an execution. */
    private NusaResultSet outRow;
    private boolean lastWasNull;

    NusaCallableStatement(NusaConnection connection, String sql, boolean hasReturn) throws SQLException {
        super(connection, sql);
        this.hasReturn = hasReturn;
    }

    /** OUT parameters are read from the whole first row, so a call is never read in chunks. */
    @Override
    boolean streams() {
        return false;
    }

    @Override
    int statementIndex(int jdbcIndex) throws SQLException {
        if (!hasReturn) {
            return jdbcIndex;
        }
        return jdbcIndex - 1; // 0 for the return value, which has no statement parameter
    }

    // --- OUT parameters ---

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        ensureOpen();
        if (parameterIndex < 1) {
            throw new SQLException("nusadb: parameter index must be >= 1", "07009");
        }
        outTypes.put(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
        registerOutParameter(parameterIndex, vendorType(sqlType));
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType, int scale) throws SQLException {
        registerOutParameter(parameterIndex, vendorType(sqlType));
    }

    @Override
    public void registerOutParameter(int parameterIndex, SQLType sqlType, String typeName) throws SQLException {
        registerOutParameter(parameterIndex, vendorType(sqlType));
    }

    private static int vendorType(SQLType type) {
        Integer n = type == null ? null : type.getVendorTypeNumber();
        return n == null ? Types.OTHER : n;
    }

    /**
     * With OUT parameters registered, the result's first row is kept for their getters and the
     * statement reports no result set (the values are read through the OUT parameters).
     */
    @Override
    void apply(Wire.Result result) {
        if (outTypes.isEmpty() || result.columns.isEmpty()) {
            outRow = null;
            super.apply(result);
            return;
        }
        NusaResultSet row = new NusaResultSet(this, result);
        try {
            outRow = row.next() ? row : null;
        } catch (SQLException e) {
            outRow = null;
        }
        super.apply(new Wire.Result(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), result.tag));
    }

    /** The result column holding OUT parameter {@code parameterIndex}: its rank among the OUT parameters. */
    private int column(int parameterIndex) throws SQLException {
        Integer type = outTypes.get(parameterIndex);
        if (type == null) {
            throw new SQLException("nusadb: parameter " + parameterIndex + " was not registered as an OUT parameter", "07009");
        }
        if (outRow == null) {
            throw new SQLException("nusadb: no OUT values: the call has not been executed or returned no row", "24000");
        }
        int rank = outTypes.headMap(parameterIndex).size() + 1;
        if (rank > outRow.getMetaData().getColumnCount()) {
            throw new SQLException("nusadb: the call returned fewer values than OUT parameters", "07009");
        }
        return rank;
    }

    private <T> T track(T value) throws SQLException {
        lastWasNull = outRow.wasNull();
        return value;
    }

    @Override
    public boolean wasNull() {
        return lastWasNull;
    }

    @Override
    public String getString(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getString(c));
    }

    @Override
    public String getNString(int parameterIndex) throws SQLException {
        return getString(parameterIndex);
    }

    @Override
    public boolean getBoolean(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getBoolean(c));
    }

    @Override
    public byte getByte(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getByte(c));
    }

    @Override
    public short getShort(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getShort(c));
    }

    @Override
    public int getInt(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getInt(c));
    }

    @Override
    public long getLong(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getLong(c));
    }

    @Override
    public float getFloat(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getFloat(c));
    }

    @Override
    public double getDouble(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getDouble(c));
    }

    @Override
    public BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getBigDecimal(c));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
        BigDecimal v = getBigDecimal(parameterIndex);
        return v == null ? null : v.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getBytes(c));
    }

    @Override
    public Date getDate(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getDate(c));
    }

    @Override
    public Date getDate(int parameterIndex, Calendar cal) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getDate(c, cal));
    }

    @Override
    public Time getTime(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getTime(c));
    }

    @Override
    public Time getTime(int parameterIndex, Calendar cal) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getTime(c, cal));
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getTimestamp(c));
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex, Calendar cal) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getTimestamp(c, cal));
    }

    @Override
    public Array getArray(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getArray(c));
    }

    @Override
    public Clob getClob(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getClob(c));
    }

    @Override
    public NClob getNClob(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getNClob(c));
    }

    @Override
    public Blob getBlob(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getBlob(c));
    }

    @Override
    public java.io.Reader getCharacterStream(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getCharacterStream(c));
    }

    @Override
    public java.io.Reader getNCharacterStream(int parameterIndex) throws SQLException {
        return getCharacterStream(parameterIndex);
    }

    /** The value as the Java type of the type it was registered with. */
    @Override
    public Object getObject(int parameterIndex) throws SQLException {
        int c = column(parameterIndex);
        Object v;
        switch (outTypes.get(parameterIndex)) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
                v = outRow.getInt(c);
                break;
            case Types.BIGINT:
                v = outRow.getLong(c);
                break;
            case Types.REAL:
                v = outRow.getFloat(c);
                break;
            case Types.FLOAT:
            case Types.DOUBLE:
                v = outRow.getDouble(c);
                break;
            case Types.NUMERIC:
            case Types.DECIMAL:
                v = outRow.getBigDecimal(c);
                break;
            case Types.BIT:
            case Types.BOOLEAN:
                v = outRow.getBoolean(c);
                break;
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
                v = outRow.getString(c);
                break;
            case Types.DATE:
                v = outRow.getDate(c);
                break;
            case Types.TIME:
                v = outRow.getTime(c);
                break;
            case Types.TIMESTAMP:
                v = outRow.getTimestamp(c);
                break;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                v = outRow.getBytes(c);
                break;
            default:
                v = outRow.getObject(c);
                break;
        }
        lastWasNull = outRow.wasNull();
        return lastWasNull ? null : v;
    }

    @Override
    public Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {
        return getObject(parameterIndex);
    }

    @Override
    public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
        int c = column(parameterIndex);
        return track(outRow.getObject(c, type));
    }
}
