package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * TLS against a server started with {@code --tls-cert/--tls-key}. Runs only when
 * {@code NUSADB_TEST_TLS_URL} names one, e.g.
 * {@code jdbc:nusadb://user:pass@localhost:5680/nusadb?tls=true&tlsCa=/path/server.crt};
 * skipped otherwise.
 */
class TlsIT {

    private static String url;

    @BeforeAll
    static void setting() throws Exception {
        Class.forName("com.nusadb.jdbc.NusaDriver");
        url = System.getenv("NUSADB_TEST_TLS_URL");
        if (url == null || url.isBlank()) {
            url = System.getProperty("nusadb.test.tls.url");
        }
    }

    private static void needServer() {
        assumeTrue(url != null && !url.isBlank(), "NUSADB_TEST_TLS_URL is not set");
    }

    /** {@code url} with every TLS option removed, so a test can add its own. */
    private static String withoutTlsOptions() {
        return url.replaceAll("[?&](tls|tlsCa|tlsInsecure|tlsDomain)=[^&]*", "").replaceFirst("&", "?");
    }

    private static String join(String base, String options) {
        return base + (base.contains("?") ? "&" : "?") + options;
    }

    @Test
    void queriesOverTls() throws SQLException {
        needServer();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 41 + 1")) {
            rs.next();
            assertEquals(42, rs.getInt(1));
        }
    }

    @Test
    void anUntrustedCertificateIsRefused() {
        needServer();
        // TLS on, but with the JDK trust store, which does not know the self-signed test certificate.
        SQLException e = assertThrows(SQLException.class,
                () -> DriverManager.getConnection(join(withoutTlsOptions(), "tls=true")));
        assertEquals("08001", e.getSQLState());
    }

    @Test
    void insecureModeSkipsVerification() throws SQLException {
        needServer();
        try (Connection c = DriverManager.getConnection(join(withoutTlsOptions(), "tls=true&tlsInsecure=true"))) {
            assertTrue(c.isValid(2));
        }
    }

    @Test
    void aPlaintextClientFailsCleanly() {
        needServer();
        long t0 = System.nanoTime();
        SQLException e = assertThrows(SQLException.class,
                () -> DriverManager.getConnection(join(withoutTlsOptions(), "connectTimeout=5")));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 8000, "no hang against a TLS port");
        assertTrue(e.getSQLState().startsWith("08"), e.getSQLState());
    }

    @Test
    void cancelTravelsOverTlsToo() throws SQLException {
        needServer();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.setQueryTimeout(1);
            assertThrows(SQLTimeoutException.class, () -> st.execute(TimeoutIT.SLOW));
            assertTrue(c.isValid(2));
        }
    }
}
