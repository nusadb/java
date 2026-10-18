package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The driver under HikariCP: concurrency, connection state reset, timeouts, broken connections. */
class PoolIT {

    private static HikariDataSource pool;

    @BeforeAll
    static void start() throws SQLException {
        NusaTestServer s = NusaTestServer.get();
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSourceClassName(NusaDataSource.class.getName());
        cfg.addDataSourceProperty("url", s.url());
        cfg.setMaximumPoolSize(5);
        // Generous on purpose: over a slow link 20 threads queue for 5 connections, and the pool
        // does not promise first-come first-served, so one thread can wait well past the median.
        cfg.setConnectionTimeout(60_000);
        cfg.setValidationTimeout(2_000);
        cfg.setPoolName("nusadb-it");
        pool = new HikariDataSource(cfg);
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS pool_t");
            st.execute("CREATE TABLE pool_t (id INT PRIMARY KEY, n INT)");
        }
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void manyThreadsShareFiveConnections() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(20);
        AtomicInteger next = new AtomicInteger();
        List<Future<?>> work = new ArrayList<>();
        for (int t = 0; t < 20; t++) {
            work.add(threads.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    int id = next.incrementAndGet();
                    try (Connection c = pool.getConnection()) {
                        c.setAutoCommit(false);
                        try (Statement st = c.createStatement()) {
                            st.executeUpdate("INSERT INTO pool_t VALUES (" + id + ", " + id + ")");
                        }
                        c.commit();
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : work) {
            f.get(2, TimeUnit.MINUTES);
        }
        threads.shutdown();
        try (Connection c = pool.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM pool_t")) {
            rs.next();
            assertEquals(200, rs.getInt(1));
        }
        assertTrue(pool.getHikariPoolMXBean().getTotalConnections() <= 5);
    }

    @Test
    void poolResetsIsolationAndAutocommit() throws SQLException {
        int defaultLevel;
        try (Connection c = pool.getConnection()) {
            defaultLevel = c.getTransactionIsolation();
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
        }
        for (int i = 0; i < 10; i++) { // whichever physical connection comes back, it is reset
            try (Connection c = pool.getConnection()) {
                assertEquals(defaultLevel, c.getTransactionIsolation());
                assertTrue(c.getAutoCommit());
            }
        }
    }

    @Test
    void queryTimeoutInsideThePool() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(1);
            assertThrows(SQLTimeoutException.class, () -> st.execute(TimeoutIT.SLOW));
        }
        try (Connection c = pool.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
        }
    }

    @Test
    void aBrokenConnectionIsEvicted() throws Exception {
        try (Connection c = pool.getConnection()) {
            // Break the physical connection underneath the pool's proxy.
            NusaConnection raw = c.unwrap(NusaConnection.class);
            raw.wire.forceClose();
            assertThrows(SQLException.class, () -> c.createStatement().execute("SELECT 1"));
        }
        for (int i = 0; i < 10; i++) {
            try (Connection c = pool.getConnection(); Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next(), "the pool hands out working connections only");
            }
        }
    }
}
