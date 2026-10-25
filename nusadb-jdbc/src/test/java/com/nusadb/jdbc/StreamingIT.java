package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Results read in chunks (setFetchSize): correctness around the open stream and the transaction. */
class StreamingIT {

    private static final String MANY = "SELECT g FROM generate_series(1, 200000) AS g";

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void table(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS st_t");
            st.execute("CREATE TABLE st_t (id INT PRIMARY KEY, v TEXT)");
        }
    }

    @Test
    void everyRowArrivesInChunks() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.setFetchSize(1000);
            long sum = 0;
            int n = 0;
            try (ResultSet rs = st.executeQuery(MANY)) {
                assertEquals(1000, rs.getFetchSize());
                rs.setFetchSize(5000); // larger chunks from here on
                while (rs.next()) {
                    sum += rs.getLong(1);
                    n++;
                }
            }
            assertEquals(200_000, n);
            assertEquals(200_000L * 200_001 / 2, sum);
            assertEquals(1, scalar(c, "SELECT 1"), "the connection is ready after the stream");
        }
    }

    @Test
    void preparedStatementsStreamWithParameters() throws SQLException {
        try (Connection c = NusaTestServer.get().connect();
                PreparedStatement ps = c.prepareStatement("SELECT g FROM generate_series(1, 200000) AS g WHERE g > ?")) {
            ps.setFetchSize(500);
            ps.setMaxRows(1234);
            ps.setInt(1, 100);
            int n = 0;
            long first = -1;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (n++ == 0) {
                        first = rs.getLong(1);
                    }
                }
            }
            assertEquals(1234, n, "maxRows still applies");
            assertEquals(101, first);
        }
    }

    @Test
    void earlyCloseInATransactionKeepsTheWorkBefore() throws SQLException {
        try (Connection c = NusaTestServer.get().connect()) {
            table(c);
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO st_t VALUES (1, 'before')");
                st.setFetchSize(100);
                try (ResultSet rs = st.executeQuery(MANY)) {
                    for (int i = 0; i < 10; i++) {
                        assertTrue(rs.next());
                    }
                } // closed early: the query is cancelled and rolled back to the driver's savepoint
                st.execute("INSERT INTO st_t VALUES (2, 'after')");
            }
            c.commit();
            c.setAutoCommit(true);
            assertEquals(2, scalar(c, "SELECT count(*) FROM st_t"), "both inserts were kept");
        }
    }

    @Test
    void writesWhileStreamingAreKeptAfterAnEarlyClose() throws SQLException {
        try (Connection c = NusaTestServer.get().connect()) {
            table(c);
            c.setAutoCommit(false);
            try (Statement reader = c.createStatement(); Statement writer = c.createStatement()) {
                reader.setFetchSize(50);
                try (ResultSet rs = reader.executeQuery("SELECT g FROM generate_series(1, 1000) AS g")) {
                    for (int i = 0; i < 20; i++) {
                        assertTrue(rs.next());
                        writer.executeUpdate("INSERT INTO st_t VALUES (" + rs.getInt(1) + ", 'during')");
                    }
                } // the rest was read ahead by the first write, so nothing is cancelled or rolled back
            }
            c.commit();
            c.setAutoCommit(true);
            assertEquals(20, scalar(c, "SELECT count(*) FROM st_t"));
        }
    }

    @Test
    void anErrorPartWayStillAbortsTheTransaction() throws SQLException {
        try (Connection c = NusaTestServer.get().connect()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.setFetchSize(100);
                SQLException e = assertThrows(SQLException.class, () -> {
                    try (ResultSet rs = st.executeQuery("SELECT 10 / (50000 - g) FROM generate_series(1, 100000) AS g")) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                });
                assertEquals("22012", e.getSQLState());
                assertEquals("25P02", assertThrows(SQLException.class, () -> scalar(c, "SELECT 1")).getSQLState(),
                        "as on other servers, the failed query aborts the transaction");
            }
            c.rollback();
            c.setAutoCommit(true);
            assertEquals(1, scalar(c, "SELECT 1"));
        }
    }

    @Test
    void autocommitStreamsAndClosesEarly() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.setFetchSize(100);
            try (ResultSet rs = st.executeQuery(MANY)) {
                assertTrue(rs.next());
            }
            assertTrue(c.getAutoCommit());
            assertEquals(7, scalar(c, "SELECT 7"));
        }
    }

    @Test
    void reExecutingClosesTheOpenStream() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.setFetchSize(100);
            ResultSet first = st.executeQuery(MANY);
            assertTrue(first.next());
            try (ResultSet second = st.executeQuery("SELECT 42")) {
                assertTrue(first.isClosed(), "JDBC: a new execution closes the previous result set");
                assertTrue(second.next());
                assertEquals(42, second.getInt(1));
                assertFalse(second.next());
            }
        }
    }
}
