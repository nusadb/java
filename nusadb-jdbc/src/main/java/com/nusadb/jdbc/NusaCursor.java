package com.nusadb.jdbc;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * The rows behind a result set read in chunks ({@code setFetchSize}): it takes the next chunk of
 * {@link Wire.RowStream} when the result set runs out of rows, stops at {@code maxRows}, and when
 * the result set is closed before its end reads and drops the rows left.
 */
final class NusaCursor {
    private final Wire.RowStream stream;
    private int fetchSize;
    /** Rows the result set may still deliver under {@code maxRows}; -1 for no limit. */
    private long remaining;
    private boolean closed;

    NusaCursor(Wire.RowStream stream, int fetchSize, int maxRows) {
        this.stream = stream;
        this.fetchSize = fetchSize;
        this.remaining = maxRows > 0 ? maxRows : -1;
    }

    /** The next chunk of rows; empty when there are none (left, or within {@code maxRows}). */
    List<List<byte[]>> fetch() throws SQLException {
        if (closed || remaining == 0) {
            close();
            return Collections.emptyList();
        }
        int chunk = remaining < 0 ? fetchSize : (int) Math.min(fetchSize, remaining);
        List<List<byte[]>> rows;
        try {
            rows = stream.next(chunk);
        } catch (SQLException e) {
            closed = true;
            throw e;
        }
        if (remaining > 0) {
            remaining -= rows.size();
        }
        if (rows.isEmpty() || remaining == 0) {
            close();
        }
        return rows;
    }

    boolean hasMore() {
        return !closed && !stream.exhausted();
    }

    void setFetchSize(int rows) {
        if (rows > 0) {
            fetchSize = rows;
        }
    }

    int getFetchSize() {
        return fetchSize;
    }

    /** Reads and drops the rows the result set did not reach, so the connection is ready again. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stream.discard();
        } catch (SQLException ignored) {
            // the connection is broken and closed; nothing is left to read
        }
    }
}
