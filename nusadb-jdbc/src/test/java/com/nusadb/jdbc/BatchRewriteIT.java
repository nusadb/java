package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code rewriteBatchedInserts}: same rows, far fewer round trips. */
class BatchRewriteIT {

    private static final int ROWS = 40_000; // more than one chunk at 2 parameters per row

    @BeforeAll
    static void driver() throws Exception {
        Class.forName("com.nusadb.jdbc.NusaDriver");
    }

    private static Connection open(boolean rewrite) throws SQLException {
        return DriverManager.getConnection(NusaTestServer.get().url() + (rewrite ? "?rewriteBatchedInserts=true" : ""));
    }

    private static long load(Connection c, String table, int rows) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, name TEXT)");
        }
        long t0 = System.nanoTime();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (id, name) VALUES (?, ?)")) {
            for (int i = 0; i < rows; i++) {
                ps.setInt(1, i);
                ps.setString(2, "n'" + i); // a quote, to keep the literal handling honest
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            assertEquals(rows, counts.length);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void rewrittenBatchLoadsEveryRow() throws SQLException {
        try (Connection c = open(true)) {
            long ms = load(c, "brw_t", ROWS);
            assertEquals(Integer.toString(ROWS), scalar(c, "SELECT count(*) FROM brw_t"));
            assertEquals("n'39999", scalar(c, "SELECT name FROM brw_t WHERE id = 39999"));
            System.out.println("[batch] " + ROWS + " rows rewritten in " + ms + " ms");
        }
    }

    @Test
    void rewrittenBatchReportsNoInfoCounts() throws SQLException {
        try (Connection c = open(true); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS brw_c");
            st.execute("CREATE TABLE brw_c (id INT PRIMARY KEY)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO brw_c VALUES (?)")) {
                for (int i = 0; i < 5; i++) {
                    ps.setInt(1, i);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                assertTrue(Arrays.stream(counts).allMatch(n -> n == Statement.SUCCESS_NO_INFO));
            }
        }
    }

    @Test
    void rewriteIsFasterThanRowByRow() throws SQLException {
        int rows = 1000;
        long plain;
        long rewritten;
        try (Connection c = open(false)) {
            plain = load(c, "brw_plain", rows);
        }
        try (Connection c = open(true)) {
            rewritten = load(c, "brw_fast", rows);
        }
        System.out.println("[batch] " + rows + " rows: row by row " + plain + " ms, rewritten " + rewritten + " ms");
        assertTrue(rewritten < plain, "rewritten " + rewritten + " ms vs row by row " + plain + " ms");
    }

    @Test
    void otherStatementsStillBatchRowByRow() throws SQLException {
        try (Connection c = open(true); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS brw_u");
            st.execute("CREATE TABLE brw_u (id INT PRIMARY KEY, n INT)");
            st.execute("INSERT INTO brw_u VALUES (1, 0), (2, 0)");
            try (PreparedStatement ps = c.prepareStatement("UPDATE brw_u SET n = ? WHERE id = ?")) {
                ps.setInt(1, 5);
                ps.setInt(2, 1);
                ps.addBatch();
                ps.setInt(1, 7);
                ps.setInt(2, 2);
                ps.addBatch();
                assertEquals("[1, 1]", Arrays.toString(ps.executeBatch()));
            }
        }
    }
}
