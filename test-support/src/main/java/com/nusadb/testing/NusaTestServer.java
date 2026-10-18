package com.nusadb.testing;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The live NusaDB server an integration test talks to. One server per JVM, started on first use and
 * shared by every test class; it always requires authentication, so {@link #url()} carries
 * credentials.
 *
 * <p>Where the server comes from, first match wins:
 *
 * <ol>
 *   <li>{@code NUSADB_TEST_URL}: an already running server, e.g.
 *       {@code jdbc:nusadb://user:pass@127.0.0.1:5679/nusadb}. <b>Every table, view and sequence in that
 *       database is dropped before the first test</b>, so point it at a scratch database only.
 *   <li>{@code NUSADB_SERVER_BIN}: path to a {@code nusadb-server} binary (e.g. from
 *       {@code cargo build -p nusadb-server}), started on an ephemeral port with a temp data dir.
 *   <li>Otherwise a Testcontainers container of {@code NUSADB_TEST_IMAGE} (default
 *       {@value #DEFAULT_IMAGE}); this needs Docker.
 * </ol>
 *
 * Each variable can also be given as a system property: {@code nusadb.test.url},
 * {@code nusadb.server.bin}, {@code nusadb.test.image}.
 */
public final class NusaTestServer {

    public static final String DEFAULT_IMAGE = "nusadb/nusadb:0.1.0-beta.15";

    private static final String USER = "nusa_test";
    private static final String PASSWORD = "nusa_test_pw";
    private static final String DATABASE = "nusadb";
    private static final int SERVER_PORT = 5678;
    private static final Pattern URL =
            Pattern.compile("jdbc:nusadb://(?:([^:@/]+)(?::([^@/]*))?@)?([^:/]+)(?::(\\d+))?/([^?]+).*");

    private static NusaTestServer instance;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String database;
    private final String description;

    private NusaTestServer(String host, int port, String user, String password, String database, String description) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.database = database;
        this.description = description;
    }

    /** The shared server, started (or attached to) on first call. */
    public static synchronized NusaTestServer get() {
        if (instance == null) {
            instance = start();
            instance.awaitReady();
            System.out.println("[nusadb-test] using " + instance.description);
        }
        return instance;
    }

    /** JDBC URL with the server's credentials. */
    public String url() {
        return url(password);
    }

    /** JDBC URL for the same user with a different password (for authentication-failure tests). */
    public String url(String password) {
        return "jdbc:nusadb://" + user + ":" + password + "@" + host + ":" + port + "/" + database;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public String database() {
        return database;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url());
    }

    // --- startup -------------------------------------------------------------

    private static NusaTestServer start() {
        String url = setting("NUSADB_TEST_URL", "nusadb.test.url");
        if (url != null) {
            NusaTestServer s = external(url);
            s.awaitReady();
            s.resetDatabase();
            return s;
        }
        String bin = setting("NUSADB_SERVER_BIN", "nusadb.server.bin");
        if (bin != null) {
            return binary(bin);
        }
        String image = setting("NUSADB_TEST_IMAGE", "nusadb.test.image");
        return container(image != null ? image : DEFAULT_IMAGE);
    }

    private static NusaTestServer external(String url) {
        Matcher m = URL.matcher(url);
        if (!m.matches() || m.group(1) == null) {
            throw new IllegalStateException(
                    "NUSADB_TEST_URL must look like jdbc:nusadb://user:password@host:port/database, got " + url);
        }
        int port = m.group(4) != null ? Integer.parseInt(m.group(4)) : SERVER_PORT;
        String pw = m.group(2) != null ? m.group(2) : "";
        return new NusaTestServer(m.group(3), port, m.group(1), pw, m.group(5),
                "external server " + m.group(3) + ":" + port + "/" + m.group(5));
    }

    private static NusaTestServer binary(String bin) {
        try {
            int port = freePort();
            File dir = Files.createTempDirectory("nusadb-test-").toFile();
            List<String> cmd = new ArrayList<>(List.of(bin, "--listen", "127.0.0.1:" + port,
                    "--data-dir", dir.getAbsolutePath(), "--auth-user", USER + ":" + PASSWORD));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("RUST_LOG", "error");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                process.destroy();
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                deleteRecursively(dir);
            }));
            return new NusaTestServer("127.0.0.1", port, USER, PASSWORD, DATABASE, "server binary " + bin);
        } catch (IOException e) {
            throw new IllegalStateException("could not start " + bin, e);
        }
    }

    @SuppressWarnings("resource") // stopped by Testcontainers' Ryuk when the JVM exits
    private static NusaTestServer container(String image) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
                .withEnv("NUSADB_USER", USER)
                .withEnv("NUSADB_PASSWORD", PASSWORD)
                .withExposedPorts(SERVER_PORT)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        c.start();
        return new NusaTestServer(c.getHost(), c.getMappedPort(SERVER_PORT), USER, PASSWORD, DATABASE,
                "container " + image);
    }

    /** A listening socket is not yet a ready server: retry a real login until it succeeds. */
    private void awaitReady() {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("NusaDB at " + host + ":" + port + " did not accept logins", last);
    }

    /**
     * Drop every view, table and sequence in the external database, so a run starts from the same
     * empty state a fresh container or binary gives. Tables are retried until none are left, which
     * sorts out foreign-key ordering without having to compute it.
     */
    private void resetDatabase() {
        try (Connection c = connect()) {
            for (String v : names(c, "SELECT table_name FROM information_schema.views WHERE table_schema = 'public'")) {
                exec(c, "DROP VIEW IF EXISTS \"" + v + "\"");
            }
            for (int round = 0; round < 10; round++) {
                List<String> tables = names(c,
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                                + "AND table_type = 'BASE TABLE'");
                if (tables.isEmpty()) {
                    break;
                }
                for (String t : tables) {
                    try {
                        exec(c, "DROP TABLE IF EXISTS \"" + t + "\" CASCADE");
                    } catch (SQLException retryNextRound) {
                        // still referenced; the next round gets it
                    }
                }
            }
            for (String s : names(c, "SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = 'public'")) {
                exec(c, "DROP SEQUENCE IF EXISTS \"" + s + "\"");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not reset the test database at " + description, e);
        }
    }

    private static List<String> names(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String setting(String env, String property) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        // best effort
        f.delete();
    }
}
