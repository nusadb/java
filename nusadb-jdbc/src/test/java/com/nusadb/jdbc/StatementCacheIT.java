package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Server-side statements kept per connection and reused by later PreparedStatements. */
class StatementCacheIT {

    private static Connection connect(String options) throws SQLException {
        String url = NusaTestServer.get().url();
        return DriverManager.getConnection(url + (url.contains("?") ? "&" : "?") + options);
    }

    private static int run(Connection c, String sql, int param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static StatementCache cache(Connection c) throws SQLException {
        return c.unwrap(NusaConnection.class).statements;
    }

    @Test
    void theSameSqlReusesItsServerStatement() throws SQLException {
        try (Connection c = NusaTestServer.get().connect()) {
            for (int i = 0; i < 100; i++) {
                assertEquals(i + 1, run(c, "SELECT ? + 1", i));
            }
            // one untyped statement (checked at prepareStatement) and one declaring the int parameter
            assertEquals(2, cache(c).size());
            assertEquals(5, run(c, "SELECT ? + 2", 3));
            assertEquals(4, cache(c).size());
        }
    }

    @Test
    void statementsBeyondTheCapacityAreClosedAndPreparedAgainWhenNeeded() throws SQLException {
        try (Connection c = connect("preparedStatementCacheQueries=3")) {
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 10; i++) {
                    assertEquals(i * 10 + 7, run(c, "SELECT " + (i * 10) + " + ?", 7));
                }
            }
            assertTrue(cache(c).size() <= 3);
            assertTrue(c.isValid(2));
        }
    }

    @Test
    void anOpenStatementSurvivesTheEvictionOfItsServerStatement() throws SQLException {
        try (Connection c = connect("preparedStatementCacheQueries=2");
                PreparedStatement kept = c.prepareStatement("SELECT ? * 2")) {
            kept.setInt(1, 21);
            try (ResultSet rs = kept.executeQuery()) {
                rs.next();
                assertEquals(42, rs.getInt(1));
            }
            for (int i = 0; i < 5; i++) {
                run(c, "SELECT " + i + " + ?", 1); // pushes the kept statement out of the cache
            }
            kept.setInt(1, 5);
            try (ResultSet rs = kept.executeQuery()) {
                rs.next();
                assertEquals(10, rs.getInt(1), "prepared again on its next use");
            }
        }
    }

    @Test
    void aCapacityOfZeroKeepsNothing() throws SQLException {
        try (Connection c = connect("preparedStatementCacheQueries=0")) {
            for (int i = 0; i < 50; i++) {
                assertEquals(i, run(c, "SELECT ? + 0", i));
            }
            assertEquals(0, cache(c).size());
        }
    }

    @Test
    void aBadCapacityIsRejected() {
        SQLException e = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> connect("preparedStatementCacheQueries=-1").close());
        assertEquals("08001", e.getSQLState());
    }
}
