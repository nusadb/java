package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Isolation, row limits, statement lifecycle, foreign-key metadata, versions and the DataSource. */
class ConnectionIT {

    private static Connection open() throws SQLException {
        return NusaTestServer.get().connect();
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void isolationLevelIsAppliedAndReported() throws SQLException {
        try (Connection c = open()) {
            DatabaseMetaData md = c.getMetaData();
            assertTrue(md.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));
            for (int level : new int[] {Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_READ_COMMITTED,
                    Connection.TRANSACTION_REPEATABLE_READ}) {
                c.setTransactionIsolation(level);
                assertEquals(level, c.getTransactionIsolation());
                c.setAutoCommit(false);
                c.createStatement().execute("SELECT 1"); // opens the transaction
                String inTxn = scalar(c, "SHOW transaction_isolation");
                assertEquals(level, switch (inTxn) {
                    case "serializable" -> Connection.TRANSACTION_SERIALIZABLE;
                    case "repeatable read" -> Connection.TRANSACTION_REPEATABLE_READ;
                    default -> Connection.TRANSACTION_READ_COMMITTED;
                }, "the transaction really runs at the level set");
                SQLException e = assertThrows(SQLException.class,
                        () -> c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE));
                assertEquals("25001", e.getSQLState());
                c.rollback();
                c.setAutoCommit(true);
            }
            assertThrows(SQLException.class, () -> c.setTransactionIsolation(Connection.TRANSACTION_NONE));
        }
    }

    @Test
    void maxRowsCapsTheResult() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.setMaxRows(3);
            int n = 0;
            try (ResultSet rs = st.executeQuery("SELECT * FROM generate_series(1, 10)")) {
                while (rs.next()) {
                    n++;
                }
            }
            assertEquals(3, n);
            assertEquals(3, st.getMaxRows());
        }
    }

    @Test
    void preparedStatementsAreFreedOnClose() throws SQLException {
        // Thousands of prepare/close cycles on one long-lived connection, as a pool sees them.
        try (Connection c = open()) {
            for (int i = 0; i < 2000; i++) {
                try (PreparedStatement ps = c.prepareStatement("SELECT ? + 1")) {
                    ps.setInt(1, i);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertEquals(i + 1, rs.getInt(1));
                    }
                }
            }
            assertTrue(c.isValid(2));
        }
    }

    /** Whether the server has the given information_schema view (older servers lack some). */
    private static boolean hasView(Connection c, String view) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeQuery("SELECT * FROM information_schema." + view + " LIMIT 1").close();
            return true;
        } catch (SQLException e) {
            if ("42P01".equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    @Test
    void foreignKeysOnAnOlderServerAreEmptyNotAnError() throws SQLException {
        try (Connection c = open()) {
            org.junit.jupiter.api.Assumptions.assumeFalse(hasView(c, "referential_constraints"),
                    "this server has the view; foreignKeyMetadata covers it");
            try (ResultSet rs = c.getMetaData().getImportedKeys(null, null, "anything")) {
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void foreignKeyMetadata() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(hasView(c, "referential_constraints"),
                    "the server has no information_schema.referential_constraints");
            st.execute("DROP TABLE IF EXISTS meta_line");
            st.execute("DROP TABLE IF EXISTS meta_order");
            st.execute("CREATE TABLE meta_order (region INT, num INT, CONSTRAINT meta_order_pk PRIMARY KEY (region, num))");
            st.execute("CREATE TABLE meta_line (id INT PRIMARY KEY, o_region INT, o_num INT, "
                    + "CONSTRAINT meta_line_order FOREIGN KEY (o_region, o_num) REFERENCES meta_order (region, num) "
                    + "ON DELETE CASCADE)");
            DatabaseMetaData md = c.getMetaData();
            List<String> imported = new ArrayList<>();
            try (ResultSet rs = md.getImportedKeys(null, null, "meta_line")) {
                while (rs.next()) {
                    imported.add(rs.getString("FKCOLUMN_NAME") + "->" + rs.getString("PKTABLE_NAME") + "."
                            + rs.getString("PKCOLUMN_NAME") + "#" + rs.getInt("KEY_SEQ") + " del=" + rs.getInt("DELETE_RULE")
                            + " " + rs.getString("FK_NAME") + "/" + rs.getString("PK_NAME"));
                }
            }
            assertEquals(List.of(
                    "o_region->meta_order.region#1 del=" + DatabaseMetaData.importedKeyCascade + " meta_line_order/meta_order_pk",
                    "o_num->meta_order.num#2 del=" + DatabaseMetaData.importedKeyCascade + " meta_line_order/meta_order_pk"),
                    imported);
            int exported = 0;
            try (ResultSet rs = md.getExportedKeys(null, null, "meta_order")) {
                while (rs.next()) {
                    assertEquals("meta_line", rs.getString("FKTABLE_NAME"));
                    exported++;
                }
            }
            assertEquals(2, exported);
            try (ResultSet rs = md.getCrossReference(null, null, "meta_order", null, null, "meta_line")) {
                assertTrue(rs.next());
            }
            try (ResultSet rs = md.getImportedKeys(null, null, "meta_order")) {
                assertFalse(rs.next(), "the parent imports nothing");
            }
            st.execute("DROP TABLE meta_line");
            st.execute("DROP TABLE meta_order");
        }
    }

    @Test
    void versionsComeFromTheServerAndTheBuild() throws SQLException {
        try (Connection c = open()) {
            DatabaseMetaData md = c.getMetaData();
            assertEquals(scalar(c, "SHOW server_version"), md.getDatabaseProductVersion());
            assertEquals(Version.parse(md.getDatabaseProductVersion())[1], md.getDatabaseMinorVersion());
            assertFalse(md.getDriverVersion().startsWith("0.0.0"), "driver.properties was filtered");
            NusaDriver d = new NusaDriver();
            assertEquals(md.getDriverMajorVersion(), d.getMajorVersion());
            assertEquals(md.getDriverMinorVersion(), d.getMinorVersion());
        }
    }

    @Test
    void dataSourceConnects() throws SQLException {
        NusaTestServer s = NusaTestServer.get();
        NusaDataSource ds = new NusaDataSource();
        ds.setServerName(s.host());
        ds.setPortNumber(s.port());
        ds.setDatabaseName(s.database());
        ds.setUser(s.user());
        ds.setPassword(s.password());
        ds.setConnectTimeout(5);
        ds.setSocketTimeout(30);
        try (Connection c = ds.getConnection()) {
            assertEquals("1", scalar(c, "SELECT 1"));
            assertEquals(30_000, c.getNetworkTimeout());
        }
        NusaDataSource byUrl = new NusaDataSource();
        byUrl.setUrl(s.url());
        try (Connection c = byUrl.getConnection()) {
            assertEquals("1", scalar(c, "SELECT 1"));
        }
        assertThrows(SQLException.class, () -> ds.getConnection(s.user(), "wrong"));
    }

    @Test
    void tablesAreReportedWithTheJdbcTableType() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("DROP VIEW IF EXISTS meta_kind_v");
            st.execute("DROP TABLE IF EXISTS meta_kind");
            st.execute("CREATE TABLE meta_kind (id INT PRIMARY KEY)");
            st.execute("CREATE VIEW meta_kind_v AS SELECT id FROM meta_kind");
            DatabaseMetaData md = c.getMetaData();
            List<String> all = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, null, "meta_kind%", null)) {
                while (rs.next()) {
                    all.add(rs.getString("TABLE_NAME") + ":" + rs.getString("TABLE_TYPE"));
                }
            }
            assertTrue(all.contains("meta_kind:TABLE"), all.toString());
            try (ResultSet rs = md.getTables(null, null, "meta_kind", new String[] {"TABLE", "VIEW"})) {
                assertTrue(rs.next());
                assertEquals("meta_kind", rs.getString("TABLE_NAME"));
            }
            try (ResultSet rs = md.getTables(null, null, "meta_kind", new String[] {"VIEW"})) {
                assertFalse(rs.next(), "the types filter leaves the table out");
            }
            st.execute("DROP VIEW meta_kind_v");
            st.execute("DROP TABLE meta_kind");
        }
    }

    @Test
    void savepointsAreAdvertised() throws SQLException {
        try (Connection c = open()) {
            assertTrue(c.getMetaData().supportsSavepoints());
        }
    }

    @Test
    void aClosedConnectionSaysSo() throws SQLException {
        Connection c = open();
        c.close();
        for (org.junit.jupiter.api.function.Executable call : List.<org.junit.jupiter.api.function.Executable>of(
                c::clearWarnings, c::getWarnings, c::getAutoCommit, c::getCatalog, c::getSchema, c::isReadOnly)) {
            SQLException e = assertThrows(SQLException.class, call);
            assertEquals("08003", e.getSQLState());
        }
    }
}
