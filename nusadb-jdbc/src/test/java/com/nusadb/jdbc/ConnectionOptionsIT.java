package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.ClientInfoStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** currentSchema, defaultRowFetchSize, tcpKeepAlive, loginTimeout and ApplicationName. */
class ConnectionOptionsIT {

    private static Connection open(String options) throws SQLException {
        return DriverManager.getConnection(NusaTestServer.get().url() + "?" + options);
    }

    private static String one(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void currentSchemaSetsTheSearchPath() throws SQLException {
        try (Connection c = open("currentSchema=information_schema%2C%22public%22")) {
            assertEquals("information_schema, public", one(c, "SHOW search_path"));
            assertEquals("information_schema", c.getSchema());
            c.setSchema("public");
            assertEquals("public", c.getSchema());
            assertEquals("public", one(c, "SHOW search_path"));
            c.setSchema(null);
            assertEquals("\"$user\", public", one(c, "SHOW search_path"));
        }
        try (Connection c = NusaTestServer.get().connect()) {
            assertEquals("public", c.getSchema());
        }
    }

    @Test
    void defaultRowFetchSizeIsEveryStatementsFetchSize() throws SQLException {
        try (Connection c = open("defaultRowFetchSize=40");
                Statement st = c.createStatement();
                PreparedStatement ps = c.prepareStatement("SELECT g FROM generate_series(1, $1) AS g")) {
            assertEquals(40, st.getFetchSize());
            assertEquals(40, ps.getFetchSize());
            assertEquals(40, c.prepareCall("SELECT 1").getFetchSize());
            ps.setInt(1, 101);
            int rows = 0;
            try (ResultSet rs = ps.executeQuery()) {
                assertEquals(40, rs.getFetchSize());
                while (rs.next()) {
                    rows++;
                }
            }
            assertEquals(101, rows);
        }
        SQLException bad = assertThrows(SQLException.class, () -> open("defaultRowFetchSize=-1"));
        assertEquals("08001", bad.getSQLState());
    }

    @Test
    void tcpKeepAliveConnects() throws SQLException {
        try (Connection c = open("tcpKeepAlive=true")) {
            assertEquals("1", one(c, "SELECT 1"));
        }
    }

    @Test
    void loginTimeoutBoundsASilentServer() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            List<Socket> held = new ArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        held.add(silent.accept());
                    }
                } catch (Exception ignored) {
                    // the socket is closed at the end of the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            long t0 = System.nanoTime();
            assertThrows(SQLException.class, () -> DriverManager.getConnection(
                    "jdbc:nusadb://u:p@127.0.0.1:" + silent.getLocalPort() + "/nusadb?loginTimeout=1"));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 800 && ms < 5_000, "took " + ms + " ms");
            for (Socket s : held) {
                s.close();
            }
        }
    }

    @Test
    void applicationNameIsClientInfo() throws SQLException {
        try (Connection c = open("ApplicationName=it-app")) {
            assertEquals("it-app", c.getClientInfo(NusaConnection.APPLICATION_NAME));
            c.setClientInfo(NusaConnection.APPLICATION_NAME, "it-batch");
            assertEquals("it-batch", c.getClientInfo().getProperty(NusaConnection.APPLICATION_NAME));
            SQLClientInfoException e = assertThrows(SQLClientInfoException.class,
                    () -> c.setClientInfo("ClientUser", "bob"));
            assertEquals(ClientInfoStatus.REASON_UNKNOWN_PROPERTY, e.getFailedProperties().get("ClientUser"));
            assertNull(c.getClientInfo("ClientUser"));
            c.setClientInfo(new Properties());
            assertNull(c.getClientInfo(NusaConnection.APPLICATION_NAME));
        }
    }

    @Test
    void dataSourceCarriesTheOptions() throws SQLException {
        NusaTestServer s = NusaTestServer.get();
        NusaDataSource ds = new NusaDataSource();
        ds.setUrl("jdbc:nusadb://" + s.host() + ":" + s.port() + "/" + s.database());
        ds.setUser(s.user());
        ds.setPassword(s.password());
        ds.setCurrentSchema("public");
        ds.setDefaultRowFetchSize(25);
        ds.setTcpKeepAlive(true);
        ds.setApplicationName("ds-app");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertEquals("public", one(c, "SHOW search_path"));
            assertEquals(25, st.getFetchSize());
            assertEquals("ds-app", c.getClientInfo(NusaConnection.APPLICATION_NAME));
        }
    }
}
