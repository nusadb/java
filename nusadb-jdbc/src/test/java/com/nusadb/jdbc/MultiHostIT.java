package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Multi-host URLs. The standby cases need {@code NUSADB_TEST_STANDBY_URL}: a URL naming a primary
 * and then its standby ({@code --standby-from}), e.g. {@code jdbc:nusadb://u:p@h:1,h:2/nusadb}.
 */
class MultiHostIT {
    private static final String DEAD = "127.0.0.1:1";

    private static String url(String hosts, String options) {
        NusaTestServer s = NusaTestServer.get();
        return "jdbc:nusadb://" + s.user() + ":" + s.password() + "@" + hosts + "/" + s.database()
                + (options.isEmpty() ? "" : "?" + options);
    }

    private static String real() {
        NusaTestServer s = NusaTestServer.get();
        return s.host() + ":" + s.port();
    }

    private static int one(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    @Test
    void parsesHostLists() throws SQLException {
        assertEquals(List.of(new HostSelector.Host("a", 1), new HostSelector.Host("b", 5678),
                        new HostSelector.Host("::1", 7)),
                HostSelector.parse("a:1,b,[::1]:7", "127.0.0.1", 5678));
        assertEquals(List.of(new HostSelector.Host("127.0.0.1", 5678)), HostSelector.parse("", "127.0.0.1", 5678));
        assertEquals("08001", assertThrows(SQLException.class,
                () -> HostSelector.parse("a:x", "h", 1)).getSQLState());
        assertEquals("08001", assertThrows(SQLException.class,
                () -> HostSelector.parse("a,,b", "h", 1)).getSQLState());
        assertEquals("08001", assertThrows(SQLException.class,
                () -> DriverManager.getConnection(url(real(), "targetServerType=leader"))).getSQLState());
    }

    @Test
    void skipsADeadHostAndReportsEveryFailure() throws SQLException {
        String u = url(DEAD + "," + real(), "");
        try (Connection c = DriverManager.getConnection(u)) {
            assertEquals(1, one(c));
            assertEquals(NusaDriver.withoutPassword(u), c.getMetaData().getURL());
            assertTrue(!c.getMetaData().getURL().contains(":" + NusaTestServer.get().password() + "@"));
            assertEquals(NusaTestServer.get().user(), c.getMetaData().getUserName());
        }
        SQLException e = assertThrows(SQLException.class,
                () -> DriverManager.getConnection(url(DEAD + ",127.0.0.1:2", "")));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().contains("127.0.0.1:1") && e.getMessage().contains("127.0.0.1:2"), e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void anAuthenticationFailureIsNotRetriedOnTheNextHost() {
        NusaTestServer s = NusaTestServer.get();
        String u = "jdbc:nusadb://" + s.user() + ":wrong@" + real() + "," + real() + "/" + s.database();
        SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(u));
        assertTrue(!"08001".equals(e.getSQLState()), e.getSQLState() + " " + e.getMessage());
    }

    @Test
    void loadBalanceHostsSpreadsConnections() throws Exception {
        try (Forwarder a = new Forwarder(); Forwarder b = new Forwarder()) {
            String hosts = "127.0.0.1:" + a.port() + ",127.0.0.1:" + b.port();
            for (int i = 0; i < 4; i++) {
                try (Connection c = DriverManager.getConnection(url(hosts, ""))) {
                    one(c);
                }
            }
            assertEquals(4, a.accepted.get());
            assertEquals(0, b.accepted.get());
            for (int i = 0; i < 30; i++) {
                try (Connection c = DriverManager.getConnection(url(hosts, "loadBalanceHosts=true"))) {
                    one(c);
                }
            }
            assertTrue(a.accepted.get() > 4 && b.accepted.get() > 0, a.accepted + " / " + b.accepted);
        }
    }

    @Test
    void targetTypesOnASinglePrimary() throws SQLException {
        try (Connection c = DriverManager.getConnection(url(real(), "targetServerType=primary"))) {
            assertEquals(1, one(c));
            assertTrue(c.getAutoCommit());
        }
        SQLException e = assertThrows(SQLException.class,
                () -> DriverManager.getConnection(url(real(), "targetServerType=secondary")));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().contains("no host is a standby"), e.getMessage());
        try (Connection c = DriverManager.getConnection(url(real(), "targetServerType=preferSecondary"))) {
            assertEquals(1, one(c));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NUSADB_TEST_STANDBY_URL", matches = ".+")
    void targetTypesPickThePrimaryOrTheStandby() throws SQLException {
        String u = System.getenv("NUSADB_TEST_STANDBY_URL");
        int q = u.indexOf('?');
        String base = q < 0 ? u : u.substring(0, q);
        String at = base.substring(0, base.indexOf('@') + 1);
        String hosts = base.substring(at.length(), base.indexOf('/', at.length()));
        String path = base.substring(at.length() + hosts.length());
        String[] hs = hosts.split(",");
        String reversed = at + hs[1] + "," + hs[0] + path;
        for (String order : List.of(base, reversed)) {
            try (Connection c = DriverManager.getConnection(order + "?targetServerType=primary");
                    Statement st = c.createStatement()) {
                st.execute("CREATE TEMPORARY TABLE multi_host_w (id int)");
            }
            try (Connection c = DriverManager.getConnection(order + "?targetServerType=secondary");
                    Statement st = c.createStatement()) {
                SQLException e = assertThrows(SQLException.class,
                        () -> st.execute("CREATE TEMPORARY TABLE multi_host_w (id int)"));
                assertEquals("25006", e.getSQLState());
            }
        }
        try (Connection c = DriverManager.getConnection(at + hs[1] + path + "?targetServerType=preferPrimary")) {
            assertEquals(1, one(c));
        }
    }

    /** Forwards local connections to the test server and counts them. */
    private static final class Forwarder implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0);
        final AtomicInteger accepted = new AtomicInteger();

        Forwarder() throws Exception {
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Socket client = server.accept();
                        accepted.incrementAndGet();
                        Socket up = new Socket(NusaTestServer.get().host(), NusaTestServer.get().port());
                        pump(client, up);
                        pump(up, client);
                    }
                } catch (Exception closed) {
                    // the forwarder was closed
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private static void pump(Socket from, Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    for (int n; (n = in.read(buf)) >= 0; ) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignored) {
                    // one side went away
                } finally {
                    try {
                        from.close();
                        to.close();
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }
}
