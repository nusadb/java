package com.nusadb.jdbc;

import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * Bulk load / export over the Nusa wire {@code COPY} sub-protocol (wire-protocol.md §12): one
 * round-trip for the whole dataset instead of a row-per-insert.
 *
 * <p>Obtain one from a connection with {@code connection.unwrap(NusaCopyManager.class)}. Data is
 * exchanged in the server's text format: tab-delimited fields, {@code \N} for SQL {@code NULL}, one
 * row per line. You write the {@code COPY} statement (with any {@code WITH (...)} options) and the
 * manager moves the bytes.
 *
 * <pre>{@code
 * NusaCopyManager copy = conn.unwrap(NusaCopyManager.class);
 * long loaded = copy.copyIn("COPY t (id, name) FROM STDIN", inputStream);
 * long exported = copy.copyOut("COPY t TO STDOUT", outputStream);
 * }</pre>
 */
public final class NusaCopyManager {
    private final Wire wire;

    NusaCopyManager(Wire wire) {
        this.wire = wire;
    }

    /**
     * Run {@code COPY ... FROM STDIN}, streaming {@code source}'s text-format bytes to the server.
     * Returns the number of rows loaded. The stream is read to EOF but not closed.
     */
    public long copyIn(String sql, InputStream source) throws SQLException {
        return wire.copyIn(sql, source);
    }

    /**
     * Run {@code COPY ... TO STDOUT}, writing the server's text-format rows to {@code sink}. Returns
     * the number of rows exported. The stream is written but not closed or flushed beyond the writes.
     */
    public long copyOut(String sql, OutputStream sink) throws SQLException {
        return wire.copyOut(sql, sink);
    }
}
