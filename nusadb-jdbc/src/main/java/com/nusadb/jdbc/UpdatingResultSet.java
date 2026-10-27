package com.nusadb.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * Funnels every {@code updateXxx} method into {@link #update(int, Object)}, with streams, readers and
 * LOBs read into a value first, and the by-label forms resolved with {@link #findColumn}.
 */
abstract class UpdatingResultSet extends AbstractResultSet {

    /** The value {@code updateNull} sets; a plain {@code null} from {@code updateString(.., null)} means the same. */
    static final Object NULL = new Object();

    /** Records {@code value} for column {@code columnIndex} of the current or insert row. */
    abstract void update(int columnIndex, Object value) throws SQLException;

    private void set(int columnIndex, Object value) throws SQLException {
        update(columnIndex, value == null ? NULL : value);
    }

    private static byte[] read(InputStream in, long length) throws SQLException {
        if (in == null) {
            return null;
        }
        try {
            return length < 0 ? in.readAllBytes() : in.readNBytes((int) Math.min(length, Integer.MAX_VALUE));
        } catch (IOException e) {
            throw new SQLException("nusadb: could not read the stream: " + e.getMessage(), "22000", e);
        }
    }

    private static String read(Reader in, long length) throws SQLException {
        if (in == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        try {
            long left = length < 0 ? Long.MAX_VALUE : length;
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) >= 0) {
                sb.append(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            throw new SQLException("nusadb: could not read the reader: " + e.getMessage(), "22000", e);
        }
        return sb.toString();
    }

    private static String ascii(InputStream in, long length) throws SQLException {
        byte[] b = read(in, length);
        return b == null ? null : new String(b, StandardCharsets.US_ASCII);
    }

    private static Object lob(Object value) throws SQLException {
        if (value instanceof Clob c) {
            return c.getSubString(1, (int) c.length());
        }
        if (value instanceof Blob b) {
            return b.getBytes(1, (int) b.length());
        }
        return value;
    }

    // --- by index ---

    @Override public void updateNull(int i) throws SQLException { update(i, NULL); }
    @Override public void updateBoolean(int i, boolean x) throws SQLException { set(i, x); }
    @Override public void updateByte(int i, byte x) throws SQLException { set(i, x); }
    @Override public void updateShort(int i, short x) throws SQLException { set(i, x); }
    @Override public void updateInt(int i, int x) throws SQLException { set(i, x); }
    @Override public void updateLong(int i, long x) throws SQLException { set(i, x); }
    @Override public void updateFloat(int i, float x) throws SQLException { set(i, x); }
    @Override public void updateDouble(int i, double x) throws SQLException { set(i, x); }
    @Override public void updateBigDecimal(int i, BigDecimal x) throws SQLException { set(i, x); }
    @Override public void updateString(int i, String x) throws SQLException { set(i, x); }
    @Override public void updateNString(int i, String x) throws SQLException { set(i, x); }
    @Override public void updateBytes(int i, byte[] x) throws SQLException { set(i, x); }
    @Override public void updateDate(int i, Date x) throws SQLException { set(i, x); }
    @Override public void updateTime(int i, Time x) throws SQLException { set(i, x); }
    @Override public void updateTimestamp(int i, Timestamp x) throws SQLException { set(i, x); }
    @Override public void updateObject(int i, Object x) throws SQLException { set(i, lob(x)); }
    @Override public void updateObject(int i, Object x, int scaleOrLength) throws SQLException { set(i, lob(x)); }
    @Override public void updateObject(int i, Object x, SQLType t) throws SQLException { set(i, lob(x)); }
    @Override public void updateObject(int i, Object x, SQLType t, int scaleOrLength) throws SQLException { set(i, lob(x)); }
    @Override public void updateArray(int i, Array x) throws SQLException { set(i, x); }
    @Override public void updateSQLXML(int i, java.sql.SQLXML x) throws SQLException { set(i, x); }
    @Override public void updateBlob(int i, Blob x) throws SQLException { set(i, lob(x)); }
    @Override public void updateClob(int i, Clob x) throws SQLException { set(i, lob(x)); }
    @Override public void updateNClob(int i, NClob x) throws SQLException { set(i, lob(x)); }
    @Override public void updateBlob(int i, InputStream x, long length) throws SQLException { set(i, read(x, length)); }
    @Override public void updateBlob(int i, InputStream x) throws SQLException { set(i, read(x, -1)); }
    @Override public void updateClob(int i, Reader x, long length) throws SQLException { set(i, read(x, length)); }
    @Override public void updateClob(int i, Reader x) throws SQLException { set(i, read(x, -1)); }
    @Override public void updateNClob(int i, Reader x, long length) throws SQLException { set(i, read(x, length)); }
    @Override public void updateNClob(int i, Reader x) throws SQLException { set(i, read(x, -1)); }
    @Override public void updateAsciiStream(int i, InputStream x, int length) throws SQLException { set(i, ascii(x, length)); }
    @Override public void updateAsciiStream(int i, InputStream x, long length) throws SQLException { set(i, ascii(x, length)); }
    @Override public void updateAsciiStream(int i, InputStream x) throws SQLException { set(i, ascii(x, -1)); }
    @Override public void updateBinaryStream(int i, InputStream x, int length) throws SQLException { set(i, read(x, length)); }
    @Override public void updateBinaryStream(int i, InputStream x, long length) throws SQLException { set(i, read(x, length)); }
    @Override public void updateBinaryStream(int i, InputStream x) throws SQLException { set(i, read(x, -1)); }
    @Override public void updateCharacterStream(int i, Reader x, int length) throws SQLException { set(i, read(x, length)); }
    @Override public void updateCharacterStream(int i, Reader x, long length) throws SQLException { set(i, read(x, length)); }
    @Override public void updateCharacterStream(int i, Reader x) throws SQLException { set(i, read(x, -1)); }
    @Override public void updateNCharacterStream(int i, Reader x, long length) throws SQLException { set(i, read(x, length)); }
    @Override public void updateNCharacterStream(int i, Reader x) throws SQLException { set(i, read(x, -1)); }

    // --- by label ---

    @Override public void updateNull(String c) throws SQLException { updateNull(findColumn(c)); }
    @Override public void updateBoolean(String c, boolean x) throws SQLException { updateBoolean(findColumn(c), x); }
    @Override public void updateByte(String c, byte x) throws SQLException { updateByte(findColumn(c), x); }
    @Override public void updateShort(String c, short x) throws SQLException { updateShort(findColumn(c), x); }
    @Override public void updateInt(String c, int x) throws SQLException { updateInt(findColumn(c), x); }
    @Override public void updateLong(String c, long x) throws SQLException { updateLong(findColumn(c), x); }
    @Override public void updateFloat(String c, float x) throws SQLException { updateFloat(findColumn(c), x); }
    @Override public void updateDouble(String c, double x) throws SQLException { updateDouble(findColumn(c), x); }
    @Override public void updateBigDecimal(String c, BigDecimal x) throws SQLException { updateBigDecimal(findColumn(c), x); }
    @Override public void updateString(String c, String x) throws SQLException { updateString(findColumn(c), x); }
    @Override public void updateNString(String c, String x) throws SQLException { updateNString(findColumn(c), x); }
    @Override public void updateBytes(String c, byte[] x) throws SQLException { updateBytes(findColumn(c), x); }
    @Override public void updateDate(String c, Date x) throws SQLException { updateDate(findColumn(c), x); }
    @Override public void updateTime(String c, Time x) throws SQLException { updateTime(findColumn(c), x); }
    @Override public void updateTimestamp(String c, Timestamp x) throws SQLException { updateTimestamp(findColumn(c), x); }
    @Override public void updateObject(String c, Object x) throws SQLException { updateObject(findColumn(c), x); }
    @Override public void updateObject(String c, Object x, int s) throws SQLException { updateObject(findColumn(c), x, s); }
    @Override public void updateObject(String c, Object x, SQLType t) throws SQLException { updateObject(findColumn(c), x, t); }
    @Override public void updateObject(String c, Object x, SQLType t, int s) throws SQLException { updateObject(findColumn(c), x, t, s); }
    @Override public void updateArray(String c, Array x) throws SQLException { updateArray(findColumn(c), x); }
    @Override public void updateSQLXML(String c, java.sql.SQLXML x) throws SQLException { updateSQLXML(findColumn(c), x); }
    @Override public void updateBlob(String c, Blob x) throws SQLException { updateBlob(findColumn(c), x); }
    @Override public void updateClob(String c, Clob x) throws SQLException { updateClob(findColumn(c), x); }
    @Override public void updateNClob(String c, NClob x) throws SQLException { updateNClob(findColumn(c), x); }
    @Override public void updateBlob(String c, InputStream x, long l) throws SQLException { updateBlob(findColumn(c), x, l); }
    @Override public void updateBlob(String c, InputStream x) throws SQLException { updateBlob(findColumn(c), x); }
    @Override public void updateClob(String c, Reader x, long l) throws SQLException { updateClob(findColumn(c), x, l); }
    @Override public void updateClob(String c, Reader x) throws SQLException { updateClob(findColumn(c), x); }
    @Override public void updateNClob(String c, Reader x, long l) throws SQLException { updateNClob(findColumn(c), x, l); }
    @Override public void updateNClob(String c, Reader x) throws SQLException { updateNClob(findColumn(c), x); }
    @Override public void updateAsciiStream(String c, InputStream x, int l) throws SQLException { updateAsciiStream(findColumn(c), x, l); }
    @Override public void updateAsciiStream(String c, InputStream x, long l) throws SQLException { updateAsciiStream(findColumn(c), x, l); }
    @Override public void updateAsciiStream(String c, InputStream x) throws SQLException { updateAsciiStream(findColumn(c), x); }
    @Override public void updateBinaryStream(String c, InputStream x, int l) throws SQLException { updateBinaryStream(findColumn(c), x, l); }
    @Override public void updateBinaryStream(String c, InputStream x, long l) throws SQLException { updateBinaryStream(findColumn(c), x, l); }
    @Override public void updateBinaryStream(String c, InputStream x) throws SQLException { updateBinaryStream(findColumn(c), x); }
    @Override public void updateCharacterStream(String c, Reader x, int l) throws SQLException { updateCharacterStream(findColumn(c), x, l); }
    @Override public void updateCharacterStream(String c, Reader x, long l) throws SQLException { updateCharacterStream(findColumn(c), x, l); }
    @Override public void updateCharacterStream(String c, Reader x) throws SQLException { updateCharacterStream(findColumn(c), x); }
    @Override public void updateNCharacterStream(String c, Reader x, long l) throws SQLException { updateNCharacterStream(findColumn(c), x, l); }
    @Override public void updateNCharacterStream(String c, Reader x) throws SQLException { updateNCharacterStream(findColumn(c), x); }
}
