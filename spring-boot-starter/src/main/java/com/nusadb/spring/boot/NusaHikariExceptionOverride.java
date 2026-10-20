package com.nusadb.spring.boot;

import com.zaxxer.hikari.SQLExceptionOverride;
import java.sql.SQLException;

/**
 * Keeps a NusaDB connection in the HikariCP pool after an error with SQLState {@code 0A000}.
 *
 * <p>HikariCP treats {@code 0A000} ("feature not supported") as a broken connection and closes it.
 * NusaDB reports a SQL construct it does not support yet with that code, as a statement error:
 * the connection stays usable, and closing it turns the real error into a failed rollback
 * ("Connection is closed"). Every other error is left to HikariCP's own rules, so a network
 * failure ({@code 08xxx}) still closes the connection.
 */
public class NusaHikariExceptionOverride implements SQLExceptionOverride {

    static final String FEATURE_NOT_SUPPORTED = "0A000";

    @java.lang.Override
    public Override adjudicate(SQLException e) {
        return FEATURE_NOT_SUPPORTED.equals(e.getSQLState()) ? Override.DO_NOT_EVICT : Override.CONTINUE_EVICT;
    }
}
