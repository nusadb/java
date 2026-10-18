package com.nusadb.jdbc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** A forward-only, read-only {@link java.sql.ResultSet} over a collected {@link Wire.Result}. */
final class NusaResultSet extends AbstractResultSet {
    private final NusaStatement statement;
    private final List<String> columns;
    private final List<String> columnTypes;
    private final List<List<byte[]>> rows;
    private int rowIndex = -1;
    private boolean lastWasNull;
    private boolean closed;

    NusaResultSet(NusaStatement statement, Wire.Result result) {
        this.statement = statement;
        this.columns = result.columns;
        this.columnTypes = result.columnTypes;
        this.rows = result.rows;
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("nusadb: result set is closed", "24000");
        }
    }

    private byte[] cell(int columnIndex) throws SQLException {
        ensureOpen();
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new SQLException("nusadb: no current row", "24000");
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("nusadb: column index out of range: " + columnIndex, "07009");
        }
        byte[] value = rows.get(rowIndex).get(columnIndex - 1);
        lastWasNull = value == null;
        return value;
    }

    private String text(int columnIndex) throws SQLException {
        byte[] b = cell(columnIndex);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        ensureOpen();
        int i = columns.indexOf(columnLabel);
        if (i < 0) {
            throw new SQLException("nusadb: no such column: " + columnLabel, "42703");
        }
        return i + 1;
    }

    @Override
    public boolean next() throws SQLException {
        ensureOpen();
        rowIndex++;
        return rowIndex < rows.size();
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean wasNull() {
        return lastWasNull;
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return new NusaResultSetMetaData(columns, columnTypes);
    }

    @Override
    public Statement getStatement() {
        return statement;
    }

    // --- by-index getters ---

    @Override
    public String getString(int columnIndex) throws SQLException {
        return text(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return false;
        }
        return s.equalsIgnoreCase("true") || s.equals("t") || s.equals("1");
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) getLong(columnIndex);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) getLong(columnIndex);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return (int) getLong(columnIndex);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as long: " + s, "22018", e);
        }
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as double: " + s, "22018", e);
        }
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as decimal: " + s, "22018", e);
        }
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        byte[] b = cell(columnIndex);
        if (b == null) {
            return null;
        }
        // A BYTEA column arrives in text format as `\x<hex>` (protocol 1.1 type tag BYTES); decode it
        // to the raw bytes. Any other column returns its field bytes verbatim.
        if (isBytea(columnIndex)) {
            return decodeHex(new String(b, StandardCharsets.UTF_8));
        }
        return b.clone();
    }

    /** Whether result column {@code columnIndex} is a BYTEA column (typed RowDescription, 1.1). */
    private boolean isBytea(int columnIndex) {
        int i = columnIndex - 1;
        return i >= 0 && i < columnTypes.size() && "BYTES".equals(columnTypes.get(i));
    }

    /** Decode the BYTEA hex text form {@code \x<hex>} to raw bytes (empty {@code \x} → empty array). */
    private static byte[] decodeHex(String s) throws SQLException {
        String h = s.startsWith("\\x") ? s.substring(2) : s;
        if ((h.length() & 1) != 0) {
            throw new SQLException("nusadb: malformed BYTEA hex (odd length)", "22P03");
        }
        byte[] out = new byte[h.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            throw new SQLException("nusadb: malformed BYTEA hex", "22P03", e);
        }
        return out;
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            return Date.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as date: " + s, "22007", e);
        }
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            // A TIMETZ renders with its zone offset ("13:45:30+07"); java.sql.Time is zone-less,
            // so keep the local (as-displayed) time and drop the offset. Fractional seconds are
            // likewise beyond Time's "HH:MM:SS" grammar and are truncated.
            String t = s.trim().replaceFirst("(\\.\\d+)?([+-]\\d{2}(:\\d{2}(:\\d{2})?)?)?$", "");
            return Time.valueOf(t);
        } catch (IllegalArgumentException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as time: " + s, "22007", e);
        }
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            return Timestamp.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as timestamp: " + s, "22007", e);
        }
    }

    /** The column's protocol 1.1 type name (e.g. {@code "INT"}), or {@code null} if not reported. */
    private String columnType(int columnIndex) {
        int i = columnIndex - 1;
        return i >= 0 && i < columnTypes.size() ? columnTypes.get(i) : null;
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        // Return the Java type that matches the column's SQL type (JDBC fidelity), reusing the typed
        // getters above. BYTEA is handled first since it decodes the field rather than reading text.
        String type = columnType(columnIndex);
        if ("BYTES".equals(type)) {
            return getBytes(columnIndex);
        }
        String s = text(columnIndex); // also sets wasNull()
        if (s == null) {
            return null;
        }
        if (type == null) {
            return s;
        }
        switch (type) {
            case "BOOL":
                return getBoolean(columnIndex);
            case "INT":
                return getLong(columnIndex);
            case "FLOAT":
                return getDouble(columnIndex);
            case "NUMERIC":
                return getBigDecimal(columnIndex);
            case "DATE":
                return getDate(columnIndex);
            case "TIME":
            case "TIMETZ":
                return getTime(columnIndex);
            case "TIMESTAMP":
            case "TIMESTAMPTZ":
                return getTimestamp(columnIndex);
            case "UUID":
                try {
                    return UUID.fromString(s);
                } catch (IllegalArgumentException e) {
                    return s;
                }
            default:
                // TEXT / JSON / INTERVAL / ARRAY / VECTOR / unknown have no distinct Java type here.
                return s;
        }
    }

    // --- by-label getters delegate to findColumn ---

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }
}
