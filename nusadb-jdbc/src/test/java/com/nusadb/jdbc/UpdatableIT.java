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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CONCUR_UPDATABLE result sets: names, keys and the cases that are refused. */
class UpdatableIT {

    private static Statement updatable(Connection c) throws SQLException {
        return c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    }

    private static String one(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void quotedNamesCompositeKeyAndAlias() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"Upd Mixed\"");
            st.execute("CREATE TABLE \"Upd Mixed\" (\"Region\" text, n int, \"Label\" text, PRIMARY KEY (\"Region\", n))");
            st.execute("INSERT INTO \"Upd Mixed\" VALUES ('eu', 1, 'a'), ('eu', 2, 'b'), ('us', 1, 'c')");
            try (Statement u = updatable(c);
                    ResultSet rs = u.executeQuery("SELECT m.\"Region\", m.n, m.\"Label\", m.n * 10 AS ten "
                            + "FROM public.\"Upd Mixed\" m WHERE \"Region\" = 'eu' ORDER BY n")) {
                assertTrue(rs.next());
                rs.updateString("Label", "A");
                rs.updateRow();
                assertEquals("A", rs.getString("Label"));
                assertEquals(10, rs.getInt("ten"));
                SQLException e = assertThrows(SQLException.class, () -> rs.updateInt("ten", 5));
                assertEquals("24000", e.getSQLState());
                assertTrue(rs.next());
                rs.updateInt("n", 20); // a key column can change too
                rs.updateRow();
                assertEquals(20, rs.getInt("n"));
                rs.refreshRow();
                assertEquals("b", rs.getString("Label"));
            }
            assertEquals("A", one(st, "SELECT \"Label\" FROM \"Upd Mixed\" WHERE \"Region\" = 'eu' AND n = 1"));
            assertEquals("b", one(st, "SELECT \"Label\" FROM \"Upd Mixed\" WHERE \"Region\" = 'eu' AND n = 20"));
            st.execute("DROP TABLE \"Upd Mixed\"");
        }
    }

    @Test
    void uuidKeyInsertIsVisibleAndRowGoneElsewhere() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS upd_uuid");
            st.execute("CREATE TABLE upd_uuid (id uuid PRIMARY KEY, v int)");
            st.execute("INSERT INTO upd_uuid VALUES ('" + UUID.randomUUID() + "', 1)");
            try (Statement u = updatable(c); ResultSet rs = u.executeQuery("SELECT * FROM upd_uuid")) {
                rs.moveToInsertRow();
                assertThrows(SQLException.class, () -> rs.getInt("v"));
                rs.updateObject("id", id);
                rs.updateInt("v", 2);
                rs.insertRow();
                rs.moveToCurrentRow();
                assertTrue(rs.last());
                assertEquals(id, rs.getObject("id", UUID.class));
                assertEquals(2, rs.getInt("v"));
                assertFalse(rs.rowInserted());
                assertTrue(rs.first());
                st.execute("DELETE FROM upd_uuid WHERE v = 1");
                rs.updateInt("v", 9);
                SQLException gone = assertThrows(SQLException.class, rs::updateRow);
                assertTrue(gone.getMessage().contains("not found"), gone.getMessage());
            }
            assertEquals("2", one(st, "SELECT v FROM upd_uuid WHERE id = '" + id + "'"));
            st.execute("DROP TABLE upd_uuid");
        }
    }

    @Test
    void readOnlyAndUnkeyedResultSetsRefuseChanges() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS upd_nokey");
            st.execute("CREATE TABLE upd_nokey (v int)");
            st.execute("INSERT INTO upd_nokey VALUES (1)");
            try (ResultSet rs = st.executeQuery("SELECT v FROM upd_nokey")) {
                assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
                assertTrue(rs.next());
                assertEquals("24000", assertThrows(SQLException.class, () -> rs.updateInt(1, 2)).getSQLState());
            }
            try (Statement u = updatable(c); ResultSet rs = u.executeQuery("SELECT v FROM upd_nokey")) {
                assertTrue(rs.next());
                SQLException e = assertThrows(SQLException.class, () -> rs.updateInt(1, 2));
                assertTrue(e.getMessage().contains("no primary key"), e.getMessage());
            }
            st.execute("DROP TABLE upd_nokey");
        }
    }

    @Test
    void anUpdatableStatementWithAFetchSizeReadsEveryRow() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS upd_many");
            st.execute("CREATE TABLE upd_many (id int PRIMARY KEY, v int)");
            st.execute("INSERT INTO upd_many SELECT g, 0 FROM generate_series(1, 50) AS g");
            try (Statement u = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
                u.setFetchSize(10);
                assertEquals(ResultSet.CONCUR_UPDATABLE, u.getResultSetConcurrency());
                try (ResultSet rs = u.executeQuery("SELECT id, v FROM upd_many ORDER BY id")) {
                    while (rs.next()) {
                        rs.updateInt("v", rs.getInt("id") * 2);
                        rs.updateRow();
                    }
                }
            }
            assertEquals("2550", one(st, "SELECT sum(v) FROM upd_many"));
            st.execute("DROP TABLE upd_many");
        }
    }
}
