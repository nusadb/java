package com.nusadb.jdbc;

import java.io.ByteArrayInputStream;
import java.io.CharArrayWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;

/**
 * A {@link Clob} (and {@link NClob}) held in memory. The server has no large-object store: a
 * {@code text} column carries the whole value, so a CLOB read from a result set is that value and
 * one bound to a statement is sent as text. Writers and streams change the value in place, as JDBC
 * describes for a client-side LOB.
 */
final class NusaClob implements NClob {
    private StringBuilder value;

    NusaClob(String value) {
        this.value = new StringBuilder(value == null ? "" : value);
    }

    /** The value of any {@link Clob}, read in full. */
    static String read(Clob clob) throws SQLException {
        if (clob instanceof NusaClob) {
            return ((NusaClob) clob).text();
        }
        long length = clob.length();
        if (length > Integer.MAX_VALUE) {
            throw new SQLException("nusadb: a CLOB over 2^31 characters cannot be sent", "22001");
        }
        return length == 0 ? "" : clob.getSubString(1, (int) length);
    }

    String text() throws SQLException {
        ensureOpen();
        return value.toString();
    }

    @Override
    public long length() throws SQLException {
        ensureOpen();
        return value.length();
    }

    @Override
    public String getSubString(long pos, int length) throws SQLException {
        ensureOpen();
        if (pos < 1 || length < 0 || pos - 1 > value.length()) {
            throw new SQLException("nusadb: CLOB position out of range", "22003");
        }
        int from = (int) (pos - 1);
        return value.substring(from, Math.min(value.length(), from + length));
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        return new StringReader(text());
    }

    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        if (length > Integer.MAX_VALUE) {
            throw new SQLException("nusadb: CLOB length out of range", "22003");
        }
        return new StringReader(getSubString(pos, (int) length));
    }

    @Override
    public InputStream getAsciiStream() throws SQLException {
        return new ByteArrayInputStream(text().getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public long position(String searchstr, long start) throws SQLException {
        ensureOpen();
        if (start < 1) {
            throw new SQLException("nusadb: CLOB position out of range", "22003");
        }
        int at = value.indexOf(searchstr, (int) Math.min(start - 1, value.length()));
        return at < 0 ? -1 : at + 1;
    }

    @Override
    public long position(Clob searchstr, long start) throws SQLException {
        return position(read(searchstr), start);
    }

    @Override
    public int setString(long pos, String str) throws SQLException {
        return setString(pos, str, 0, str.length());
    }

    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        ensureOpen();
        if (pos < 1 || pos - 1 > value.length()) {
            throw new SQLException("nusadb: CLOB position out of range", "22003");
        }
        int from = (int) (pos - 1);
        String part = str.substring(offset, offset + len);
        value.replace(from, Math.min(value.length(), from + len), part);
        return len;
    }

    @Override
    public Writer setCharacterStream(long pos) throws SQLException {
        ensureOpen();
        return new CharArrayWriter() {
            @Override
            public void close() {
                try {
                    setString(pos, toString());
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        ensureOpen();
        return new java.io.ByteArrayOutputStream() {
            @Override
            public void close() {
                try {
                    setString(pos, toString(StandardCharsets.US_ASCII));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        ensureOpen();
        if (len < 0 || len > value.length()) {
            throw new SQLException("nusadb: CLOB length out of range", "22003");
        }
        value.setLength((int) len);
    }

    @Override
    public void free() {
        value = null;
    }

    private void ensureOpen() throws SQLException {
        if (value == null) {
            throw new SQLException("nusadb: the CLOB was freed", "HY010");
        }
    }
}
