package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** PreparedStatement batches, pipelined: counts, errors part-way, and the transaction around them. */
class BatchPipelineIT {

    private static void table(Statement st) throws SQLException {
        st.execute("DROP TABLE IF EXISTS bp_t");
        st.execute("CREATE TABLE bp_t (id INT PRIMARY KEY, v TEXT, n INT)");
    }

    private static int count(Statement st, String where) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM bp_t " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void everyRowReportsItsCount() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            table(st);
            int rows = 10_000;
            long start = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bp_t VALUES (?, ?, ?)")) {
                for (int i = 0; i < rows; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "v" + i);
                    ps.setInt(3, i % 7);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                assertEquals(rows, counts.length);
                assertTrue(Arrays.stream(counts).allMatch(n -> n == 1));
            }
            System.out.println("[nusadb-test] pipelined batch of " + rows + " rows: " + (System.nanoTime() - start) / 1_000_000 + " ms");
            assertEquals(rows, count(st, ""));
            try (PreparedStatement ps = c.prepareStatement("UPDATE bp_t SET v = ? WHERE n = ?")) {
                for (int n : new int[] {0, 99, 3}) {
                    ps.setString(1, "u");
                    ps.setInt(2, n);
                    ps.addBatch();
                }
                assertArrayEquals(new int[] {1429, 0, 1429}, ps.executeBatch());
            }
            st.execute("DROP TABLE bp_t");
        }
    }

    @Test
    void rowsWithDifferentParameterTypesShareTheBatch() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            table(st);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bp_t VALUES (?, ?, ?)")) {
                for (int i = 0; i < 6; i++) {
                    ps.setInt(1, i);
                    if (i % 2 == 0) {
                        ps.setString(2, "s" + i);
                    } else {
                        ps.setNull(2, java.sql.Types.VARCHAR);
                    }
                    ps.setObject(3, i % 3 == 0 ? null : (Object) i);
                    ps.addBatch();
                }
                assertArrayEquals(new int[] {1, 1, 1, 1, 1, 1}, ps.executeBatch());
            }
            assertEquals(3, count(st, "WHERE v IS NULL"));
            st.execute("DROP TABLE bp_t");
        }
    }

    @Test
    void anErrorInATransactionReportsTheRowsBefore() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            table(st);
            st.execute("INSERT INTO bp_t VALUES (300, 'taken', 0)");
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bp_t VALUES (?, ?, 0)")) {
                for (int i = 0; i < 600; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "v" + i);
                    ps.addBatch();
                }
                BatchUpdateException e = assertThrows(BatchUpdateException.class, ps::executeBatch);
                assertEquals("23505", e.getSQLState());
                assertEquals(300, e.getUpdateCounts().length, "rows 0..299 ran before row 300 failed");
                assertTrue(Arrays.stream(e.getUpdateCounts()).allMatch(n -> n == 1));
            }
            c.rollback();
            c.setAutoCommit(true);
            assertEquals(1, count(st, ""), "the rollback undid the whole batch");
            assertTrue(c.isValid(2));
            st.execute("DROP TABLE bp_t");
        }
    }

    @Test
    void statementBatchesArePipelinedToo() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            table(st);
            for (int i = 0; i < 600; i++) {
                st.addBatch("INSERT INTO bp_t VALUES (" + i + ", 'v', " + (i % 2) + ")");
            }
            st.addBatch("UPDATE bp_t SET v = 'odd' WHERE n = 1");
            st.addBatch("DELETE FROM bp_t WHERE id = 999");
            int[] counts = st.executeBatch();
            assertEquals(602, counts.length);
            assertTrue(Arrays.stream(counts, 0, 600).allMatch(n -> n == 1));
            assertEquals(300, counts[600]);
            assertEquals(0, counts[601]);
            assertEquals(0, st.executeBatch().length, "the batch was cleared");

            st.addBatch("INSERT INTO bp_t VALUES (1000, 'a', 0)");
            st.addBatch("INSERT INTO bp_t VALUES (0, 'duplicate', 0)");
            st.addBatch("INSERT INTO bp_t VALUES (1001, 'b', 0)");
            BatchUpdateException e = assertThrows(BatchUpdateException.class, st::executeBatch);
            assertEquals("23505", e.getSQLState());
            assertArrayEquals(new int[] {1}, e.getUpdateCounts());
            assertEquals(0, count(st, "WHERE id >= 1000"), "autocommit: the failed batch left nothing behind");
            assertEquals(600, count(st, ""));
            st.execute("DROP TABLE bp_t");
        }
    }

    @Test
    void anErrorInAutocommit() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            table(st);
            st.execute("INSERT INTO bp_t VALUES (300, 'taken', 0)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bp_t VALUES (?, ?, 0)")) {
                for (int i = 0; i < 600; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "v" + i);
                    ps.addBatch();
                }
                BatchUpdateException e = assertThrows(BatchUpdateException.class, ps::executeBatch);
                assertEquals(300, e.getUpdateCounts().length);
            }
            // In autocommit the batch is one transaction: the failure undoes all of it.
            assertEquals(1, count(st, ""), "only the row that was there before");
            assertTrue(c.getAutoCommit());
            st.execute("INSERT INTO bp_t VALUES (1, 'after', 0)");
            assertEquals(2, count(st, ""), "the connection is back in autocommit, not left in a transaction");
            assertTrue(c.isValid(2));
            st.execute("DROP TABLE bp_t");
        }
    }
}
