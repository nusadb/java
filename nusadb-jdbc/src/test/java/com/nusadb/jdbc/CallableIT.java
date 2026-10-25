package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CallableStatement against the server's stored functions and procedures. */
class CallableIT {

    @BeforeEach
    void routines() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS cs_log (v int)");
            st.execute("DELETE FROM cs_log");
            st.execute("CREATE OR REPLACE FUNCTION cs_add(a int, b int) RETURNS int LANGUAGE sql AS $$ SELECT a + b $$");
            st.execute("CREATE OR REPLACE FUNCTION cs_half(a numeric) RETURNS numeric LANGUAGE sql AS $$ SELECT a / 2 $$");
            st.execute("CREATE OR REPLACE FUNCTION cs_label(n int, p text) RETURNS text LANGUAGE plpgsql AS $$ BEGIN RETURN p || n; END $$");
            st.execute("CREATE OR REPLACE PROCEDURE cs_log_value(v int) LANGUAGE sql AS $$ INSERT INTO cs_log VALUES ($1) $$");
        }
    }

    private static int logged(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT coalesce(sum(v), 0) FROM cs_log")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void returnValueIsTheFirstOutParameter() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); CallableStatement cs = c.prepareCall("{? = call cs_add(?, ?)}")) {
            cs.registerOutParameter(1, Types.INTEGER);
            cs.setInt(2, 2);
            cs.setInt(3, 3);
            assertFalse(cs.execute(), "an OUT call reports no result set");
            assertEquals(5, cs.getInt(1));
            assertEquals(5, cs.getObject(1));
            assertEquals(5L, cs.getLong(1));
            assertEquals("5", cs.getString(1));
            assertFalse(cs.wasNull());
            // executed again with other values
            cs.setInt(2, 10);
            cs.setNull(3, Types.INTEGER);
            cs.execute();
            assertEquals(0, cs.getInt(1));
            assertTrue(cs.wasNull());
            assertNull(cs.getObject(1));
        }
    }

    @Test
    void typedReturnValues() throws SQLException {
        try (Connection c = NusaTestServer.get().connect()) {
            try (CallableStatement cs = c.prepareCall("{ ? = CALL cs_half(?) }")) {
                cs.registerOutParameter(1, Types.NUMERIC);
                cs.setBigDecimal(2, new BigDecimal("7"));
                cs.execute();
                assertEquals(0, new BigDecimal("3.5").compareTo(cs.getBigDecimal(1)));
                assertTrue(cs.getObject(1) instanceof BigDecimal);
            }
            try (CallableStatement cs = c.prepareCall("{?= call cs_label(?, ?)}")) {
                cs.registerOutParameter(1, Types.VARCHAR);
                cs.setInt(2, 7);
                cs.setString(3, "n");
                cs.executeUpdate();
                assertEquals("n7", cs.getString(1));
                assertEquals("n7", cs.getObject(1, String.class));
            }
        }
    }

    @Test
    void callEscapeOfAFunctionIsAResultSet() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); CallableStatement cs = c.prepareCall("{call cs_add(?, ?)}")) {
            cs.setInt(1, 4);
            cs.setInt(2, 5);
            try (ResultSet rs = cs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(9, rs.getInt("result"));
            }
        }
    }

    @Test
    void proceduresRunNativelyOrWithCallMode() throws SQLException {
        try (Connection c = NusaTestServer.get().connect()) {
            try (CallableStatement cs = c.prepareCall("CALL cs_log_value(?)")) {
                cs.setInt(1, 1);
                cs.execute();
            }
            assertEquals(1, logged(c));
        }
        String url = NusaTestServer.get().url();
        try (Connection c = DriverManager.getConnection(url + (url.contains("?") ? "&" : "?") + "escapeSyntaxCallMode=callIfNoReturn")) {
            try (CallableStatement cs = c.prepareCall("{call cs_log_value(?)}")) {
                cs.setInt(1, 10);
                cs.execute();
            }
            assertEquals(11, logged(c));
            try (CallableStatement cs = c.prepareCall("{? = call cs_add(?, ?)}")) {
                cs.registerOutParameter(1, Types.INTEGER);
                cs.setInt(2, 1);
                cs.setInt(3, 1);
                cs.execute();
                assertEquals(2, cs.getInt(1), "callIfNoReturn still selects a function with a return value");
            }
        }
    }

    @Test
    void misuseIsReported() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); CallableStatement cs = c.prepareCall("{? = call cs_add(?, ?)}")) {
            cs.registerOutParameter(1, Types.INTEGER);
            assertEquals("24000", assertThrows(SQLException.class, () -> cs.getInt(1)).getSQLState(), "not executed yet");
            cs.setInt(2, 1);
            cs.setInt(3, 1);
            cs.execute();
            assertEquals("07009", assertThrows(SQLException.class, () -> cs.getInt(2)).getSQLState(), "an IN parameter");
            assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> cs.getInt("total"));
        }
        try (Connection c = DriverManager.getConnection(NusaTestServer.get().url() + "?escapeSyntaxCallMode=bogus")) {
            org.junit.jupiter.api.Assertions.fail("a bad call mode is rejected");
        } catch (SQLException e) {
            assertEquals("08001", e.getSQLState());
        }
    }
}
