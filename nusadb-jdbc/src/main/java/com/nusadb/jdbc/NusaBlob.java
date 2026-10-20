package com.nusadb.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * A {@link Blob} held in memory. The server has no large-object store: a {@code bytea} column
 * carries the whole value, so a BLOB read from a result set is those bytes and one bound to a
 * statement is sent as bytea. Streams change the value in place, as JDBC describes for a
 * client-side LOB.
 */
final class NusaBlob implements Blob {
    private byte[] value;
    private int length;

    NusaBlob(byte[] value) {
        this.value = value == null ? new byte[0] : value.clone();
        this.length = this.value.length;
    }

    /** The bytes of any {@link Blob}, read in full. */
    static byte[] read(Blob blob) throws SQLException {
        if (blob instanceof NusaBlob) {
            return ((NusaBlob) blob).bytes();
        }
        long n = blob.length();
        if (n > Integer.MAX_VALUE) {
            throw new SQLException("nusadb: a BLOB over 2^31 bytes cannot be sent", "22001");
        }
        return n == 0 ? new byte[0] : blob.getBytes(1, (int) n);
    }

    byte[] bytes() throws SQLException {
        ensureOpen();
        return Arrays.copyOf(value, length);
    }

    @Override
    public long length() throws SQLException {
        ensureOpen();
        return length;
    }

    @Override
    public byte[] getBytes(long pos, int len) throws SQLException {
        ensureOpen();
        if (pos < 1 || len < 0 || pos - 1 > length) {
            throw new SQLException("nusadb: BLOB position out of range", "22003");
        }
        int from = (int) (pos - 1);
        return Arrays.copyOfRange(value, from, Math.min(length, from + len));
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        ensureOpen();
        return new ByteArrayInputStream(value, 0, length);
    }

    @Override
    public InputStream getBinaryStream(long pos, long len) throws SQLException {
        if (len > Integer.MAX_VALUE) {
            throw new SQLException("nusadb: BLOB length out of range", "22003");
        }
        return new ByteArrayInputStream(getBytes(pos, (int) len));
    }

    @Override
    public long position(byte[] pattern, long start) throws SQLException {
        ensureOpen();
        if (start < 1) {
            throw new SQLException("nusadb: BLOB position out of range", "22003");
        }
        outer:
        for (int i = (int) Math.min(start - 1, length); i + pattern.length <= length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (value[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i + 1;
        }
        return -1;
    }

    @Override
    public long position(Blob pattern, long start) throws SQLException {
        return position(read(pattern), start);
    }

    @Override
    public int setBytes(long pos, byte[] bytes) throws SQLException {
        return setBytes(pos, bytes, 0, bytes.length);
    }

    @Override
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        ensureOpen();
        if (pos < 1 || pos - 1 > length) {
            throw new SQLException("nusadb: BLOB position out of range", "22003");
        }
        int from = (int) (pos - 1);
        int end = from + len;
        if (end > value.length) {
            value = Arrays.copyOf(value, Math.max(end, value.length * 2));
        }
        System.arraycopy(bytes, offset, value, from, len);
        length = Math.max(length, end);
        return len;
    }

    @Override
    public OutputStream setBinaryStream(long pos) throws SQLException {
        ensureOpen();
        return new ByteArrayOutputStream() {
            @Override
            public void close() {
                try {
                    setBytes(pos, toByteArray());
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        ensureOpen();
        if (len < 0 || len > length) {
            throw new SQLException("nusadb: BLOB length out of range", "22003");
        }
        length = (int) len;
    }

    @Override
    public void free() {
        value = null;
    }

    private void ensureOpen() throws SQLException {
        if (value == null) {
            throw new SQLException("nusadb: the BLOB was freed", "HY010");
        }
    }
}
