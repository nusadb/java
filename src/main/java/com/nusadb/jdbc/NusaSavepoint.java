package com.nusadb.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * JDBC {@link Savepoint} over the Nusa wire protocol. A savepoint is a named marker inside an open
 * transaction; {@link NusaConnection#rollback(Savepoint)} undoes work done since the marker without
 * ending the transaction, and {@link NusaConnection#releaseSavepoint(Savepoint)} forgets it.
 *
 * <p>Two flavours per the JDBC spec: a <em>named</em> savepoint (created with
 * {@link NusaConnection#setSavepoint(String)}) exposes {@link #getSavepointName()} and refuses
 * {@link #getSavepointId()}; an <em>unnamed</em> one (created with {@link NusaConnection#setSavepoint()})
 * does the reverse. Either way {@link #sqlName} is the identifier the driver actually emits in
 * {@code SAVEPOINT}/{@code ROLLBACK TO}/{@code RELEASE} statements.
 */
final class NusaSavepoint implements Savepoint {
    /** Identifier the driver emits in SQL (always set; for unnamed savepoints it is generated). */
    final String sqlName;
    private final String userName;
    private final int id;

    private NusaSavepoint(int id, String userName, String sqlName) {
        this.id = id;
        this.userName = userName;
        this.sqlName = sqlName;
    }

    /** A named savepoint: the SQL identifier is the caller's name. */
    static NusaSavepoint named(String name) {
        return new NusaSavepoint(0, name, name);
    }

    /** An unnamed savepoint: the SQL identifier is generated from the per-connection sequence. */
    static NusaSavepoint unnamed(int id) {
        return new NusaSavepoint(id, null, "nusa_sp_" + id);
    }

    @Override
    public int getSavepointId() throws SQLException {
        if (userName != null) {
            throw new SQLException("nusadb: this is a named savepoint; use getSavepointName()", "3B000");
        }
        return id;
    }

    @Override
    public String getSavepointName() throws SQLException {
        if (userName == null) {
            throw new SQLException("nusadb: this is an unnamed savepoint; use getSavepointId()", "3B000");
        }
        return userName;
    }
}
