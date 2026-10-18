package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Query timeout, cancel, network timeout, abort and connect timeout: what pools and frameworks rely on. */
class TimeoutIT {

    /** CPU-bound and streaming, so it runs for seconds without holding memory. */
    static final String SLOW = "SELECT count(*) FROM generate_series(1, 10000000) g WHERE md5(g::text) LIKE 'ab%'";

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    @BeforeAll
    static void driver() throws Exception {
        Class.forName("com.nusadb.jdbc.NusaDriver");
    }

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    private static Connection open() throws SQLException {
        return NusaTestServer.get().connect();
    }

    private static long since(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static int one(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void queryTimeoutCancelsAndKeepsTheConnection() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.setQueryTimeout(1);
            assertEquals(1, st.getQueryTimeout());
            long t0 = System.nanoTime();
            SQLTimeoutException e = assertThrows(SQLTimeoutException.class, () -> st.execute(SLOW));
            assertEquals("57014", e.getSQLState());
            assertTrue(since(t0) < 4000, "cancelled promptly, took " + since(t0) + " ms");
            assertEquals(1, one(c));
        }
    }

    @Test
    void queryTimeoutAppliesToPreparedStatements() throws SQLException {
        try (Connection c = open(); var ps = c.prepareStatement(SLOW)) {
            ps.setQueryTimeout(1);
            assertThrows(SQLTimeoutException.class, ps::executeQuery);
            assertEquals(1, one(c));
        }
    }

    @Test
    void cancelFromAnotherThread() throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            Future<?> run = POOL.submit(() -> {
                SQLException e = assertThrows(SQLException.class, () -> st.execute(SLOW));
                assertEquals("57014", e.getSQLState());
                return null;
            });
            Thread.sleep(700);
            st.cancel();
            run.get(5, TimeUnit.SECONDS);
            assertEquals(1, one(c));
            st.cancel(); // nothing running: a no-op, and the next statement is unaffected
            assertEquals(1, one(c));
        }
    }

    @Test
    void networkTimeoutClosesTheConnection() throws SQLException {
        try (Connection c = open()) {
            c.setNetworkTimeout(POOL, 500);
            assertEquals(500, c.getNetworkTimeout());
            SQLException e = assertThrows(SQLException.class, () -> c.createStatement().execute(SLOW));
            assertInstanceOf(SQLTimeoutException.class, e);
            assertTrue(c.isClosed(), "a connection whose reply timed out is closed");
            assertFalse(c.isValid(1));
        }
    }

    @Test
    void abortDoesNotWaitForTheServer() throws Exception {
        Connection c = open();
        Future<SQLException> run = POOL.submit(() -> {
            try {
                c.createStatement().execute(SLOW);
                return null;
            } catch (SQLException e) {
                return e;
            }
        });
        Thread.sleep(500);
        long t0 = System.nanoTime();
        c.abort(POOL);
        SQLException e = run.get(3, TimeUnit.SECONDS);
        assertTrue(since(t0) < 3000, "the blocked statement was released by abort");
        assertTrue(c.isClosed());
        if (e != null) {
            assertEquals("08006", e.getSQLState());
        }
        c.close(); // closing an aborted connection is harmless
    }

    @Test
    void isValidPings() throws SQLException {
        try (Connection c = open()) {
            assertTrue(c.isValid(2));
            assertThrows(SQLException.class, () -> c.isValid(-1));
        }
    }

    @Test
    void connectTimeoutFailsFastOnAnUnreachableHost() {
        // 10.255.255.1 is a non-routable address: the TCP connect just hangs without a timeout.
        long t0 = System.nanoTime();
        SQLException e = assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:nusadb://u:p@10.255.255.1:5678/nusadb?connectTimeout=1"));
        assertTrue(since(t0) < 5000, "gave up after " + since(t0) + " ms");
        assertEquals("08001", e.getSQLState());
    }
}
