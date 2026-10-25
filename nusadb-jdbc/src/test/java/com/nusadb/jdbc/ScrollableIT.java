package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** TYPE_SCROLL_INSENSITIVE result sets, and row positions on forward-only ones. */
class ScrollableIT {

    private static final String TEN = "SELECT g FROM generate_series(1, 10) AS g";

    @Test
    void scrollSensitiveBecomesInsensitive() throws SQLException {
        try (Connection c = NusaTestServer.get().connect();
                Statement st = c.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY);
                ResultSet rs = st.executeQuery(TEN)) {
            assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, st.getResultSetType());
            assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, rs.getType());
            assertTrue(rs.absolute(5));
            assertEquals(5, rs.getInt(1));
        }
    }

    @Test
    void maxRowsLimitsWhatCanBeScrolled() throws SQLException {
        try (Connection c = NusaTestServer.get().connect();
                Statement st = c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            st.setMaxRows(4);
            try (ResultSet rs = st.executeQuery(TEN)) {
                assertTrue(rs.last());
                assertEquals(4, rs.getRow());
                assertEquals(4, rs.getInt(1));
                assertFalse(rs.absolute(5));
                assertTrue(rs.isAfterLast());
            }
        }
    }

    @Test
    void fetchDirection() throws SQLException {
        try (Connection c = NusaTestServer.get().connect()) {
            try (Statement st = c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                    ResultSet rs = st.executeQuery(TEN)) {
                rs.setFetchDirection(ResultSet.FETCH_REVERSE);
                assertEquals(ResultSet.FETCH_REVERSE, rs.getFetchDirection());
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(TEN)) {
                assertThrows(SQLException.class, () -> rs.setFetchDirection(ResultSet.FETCH_REVERSE));
                assertThrows(SQLException.class, rs::last);
                assertThrows(SQLException.class, () -> rs.absolute(1));
            }
        }
    }

    @Test
    void rowNumbersOfAStreamedResultCountAcrossChunks() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.setFetchSize(3);
            try (ResultSet rs = st.executeQuery(TEN)) {
                assertTrue(rs.isBeforeFirst());
                for (int i = 1; i <= 10; i++) {
                    assertTrue(rs.next());
                    assertEquals(i, rs.getRow());
                    assertEquals(i == 1, rs.isFirst(), "row " + i);
                    assertEquals(i == 10, rs.isLast(), "row " + i);
                }
                assertFalse(rs.next());
                assertTrue(rs.isAfterLast());
                assertEquals(0, rs.getRow());
                assertFalse(rs.next(), "next after the end stays after the end");
            }
        }
    }

    @Test
    void anEmptyResultIsNeitherBeforeFirstNorAfterLast() throws SQLException {
        try (Connection c = NusaTestServer.get().connect();
                Statement st = c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                ResultSet rs = st.executeQuery("SELECT 1 WHERE false")) {
            assertFalse(rs.isBeforeFirst());
            assertFalse(rs.isAfterLast());
            assertFalse(rs.first());
            assertFalse(rs.last());
            assertFalse(rs.previous());
            assertEquals(0, rs.getRow());
        }
    }
}
