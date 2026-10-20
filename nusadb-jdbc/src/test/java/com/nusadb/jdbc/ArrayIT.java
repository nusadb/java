package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.Test;

/** Reading array values: getArray, getObject and the column metadata. */
class ArrayIT {

    @Test
    void arraysAreReadAsJdbcArrays() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT ARRAY['a', 'x y', 'b\"c', NULL], ARRAY[1, 2, 3], ARRAY[[1, 2], [3, 4]], CAST(NULL AS INT[])")) {
            assertEquals(Types.ARRAY, rs.getMetaData().getColumnType(1));
            assertEquals("java.sql.Array", rs.getMetaData().getColumnClassName(1));
            assertTrue(rs.next());
            assertArrayEquals(new Object[] {"a", "x y", "b\"c", null}, (Object[]) rs.getArray(1).getArray());
            assertArrayEquals(new Object[] {"1", "2", "3"}, (Object[]) rs.getArray(2).getArray());
            Object[] nested = (Object[]) rs.getArray(3).getArray();
            assertArrayEquals(new Object[] {"1", "2"}, (Object[]) nested[0]);
            assertArrayEquals(new Object[] {"3", "4"}, (Object[]) nested[1]);
            assertNull(rs.getArray(4));
            assertTrue(rs.wasNull());
            assertInstanceOf(Array.class, rs.getObject(2));
            assertInstanceOf(Array.class, rs.getObject(2, Array.class));
            assertEquals("{1,2,3}", rs.getString(2), "getString keeps the text form");
        }
    }

    @Test
    void anArrayColumnRoundTrips() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS arr_rt");
            st.execute("CREATE TABLE arr_rt (id INT PRIMARY KEY, tags TEXT[], empty INT[])");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO arr_rt VALUES (1, ?, ?)")) {
                ps.setArray(1, c.createArrayOf("text", new Object[] {"a", "b,c", "{x}"}));
                ps.setArray(2, c.createArrayOf("int", new Object[0]));
                ps.executeUpdate();
            }
            try (ResultSet rs = st.executeQuery("SELECT tags, empty FROM arr_rt")) {
                assertTrue(rs.next());
                assertArrayEquals(new Object[] {"a", "b,c", "{x}"}, (Object[]) rs.getArray("tags").getArray());
                assertArrayEquals(new Object[0], (Object[]) rs.getArray("empty").getArray());
            }
            st.execute("DROP TABLE arr_rt");
        }
    }

    @Test
    void malformedTextIsAnError() {
        SQLException e = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> NusaArray.parse("{1,2"));
        assertEquals("22P02", e.getSQLState());
    }
}
