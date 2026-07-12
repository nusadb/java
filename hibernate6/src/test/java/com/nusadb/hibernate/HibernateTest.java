package com.nusadb.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/**
 * Integration test for the NusaDB Hibernate 6 dialect against a real {@code nusadb-server}. JUnit-free:
 * a small harness that boots the server, builds a {@link SessionFactory} over the JDBC driver, and
 * exercises schema export, CRUD, a query with a row limit, and transaction rollback.
 *
 * <p>Run after {@code cargo build -p nusadb-server} (see the module README for the exact command).
 */
public final class HibernateTest {
    private static int passed;
    private static int failed;

    @Entity(name = "Account")
    @Table(name = "hib_account")
    public static class Account {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        @Column(name = "balance")
        private int balance;

        public Account() {}

        public Account(Long id, String name, int balance) {
            this.id = id;
            this.name = name;
            this.balance = balance;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getBalance() {
            return balance;
        }

        public void setBalance(int balance) {
            this.balance = balance;
        }
    }

    public static void main(String[] args) throws Exception {
        Server server = Server.start();
        SessionFactory sf = null;
        try {
            sf = buildSessionFactory(server.port);
            final SessionFactory factory = sf;
            run("schema export + persist + load", () -> persistAndLoad(factory));
            run("update", () -> update(factory));
            run("ordered query with limit", () -> orderedLimit(factory));
            run("delete", () -> delete(factory));
            run("transaction rollback discards", () -> rollback(factory));
        } finally {
            if (sf != null) {
                sf.close();
            }
            server.stop();
        }

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static SessionFactory buildSessionFactory(int port) {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", "jdbc:nusadb://u@127.0.0.1:" + port + "/nusadb");
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        cfg.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
        cfg.setProperty("hibernate.temp.use_jdbc_metadata_defaults", "false");
        cfg.setProperty("hibernate.show_sql", "false");
        cfg.addAnnotatedClass(Account.class);
        return cfg.buildSessionFactory();
    }

    private static void persistAndLoad(SessionFactory sf) {
        sf.inTransaction(session -> session.persist(new Account(1L, "alice", 100)));
        sf.inSession(session -> {
            Account a = session.get(Account.class, 1L);
            expect(a != null, "row loaded");
            expect("alice".equals(a.getName()), "name == alice");
            expect(a.getBalance() == 100, "balance == 100");
        });
    }

    private static void update(SessionFactory sf) {
        sf.inTransaction(session -> {
            Account a = session.get(Account.class, 1L);
            a.setBalance(250);
        });
        sf.inSession(session -> {
            Account a = session.get(Account.class, 1L);
            expect(a.getBalance() == 250, "balance updated to 250");
        });
    }

    private static void orderedLimit(SessionFactory sf) {
        sf.inTransaction(session -> {
            session.persist(new Account(2L, "bob", 50));
            session.persist(new Account(3L, "carol", 75));
        });
        sf.inSession(session -> {
            List<Account> top = session
                    .createQuery("from Account order by balance desc", Account.class)
                    .setMaxResults(1)
                    .list();
            expect(top.size() == 1, "limit 1 returns one row");
            expect("alice".equals(top.get(0).getName()), "highest balance is alice (250)");
        });
    }

    private static void delete(SessionFactory sf) {
        sf.inTransaction(session -> {
            Account a = session.get(Account.class, 2L);
            session.remove(a);
        });
        sf.inSession(session -> {
            expect(session.get(Account.class, 2L) == null, "bob deleted");
            expect(session.get(Account.class, 3L) != null, "carol survives");
        });
    }

    private static void rollback(SessionFactory sf) {
        try {
            sf.inTransaction(session -> {
                session.persist(new Account(99L, "ghost", 0));
                session.flush();
                throw new RuntimeException("force rollback");
            });
        } catch (RuntimeException expected) {
            // inTransaction rolls back when the work throws
        }
        sf.inSession(session ->
                expect(session.get(Account.class, 99L) == null, "rolled-back row absent"));
    }

    interface Check {
        void run() throws Exception;
    }

    private static void run(String name, Check check) {
        try {
            check.run();
            passed++;
            System.out.println("ok   - " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL - " + name + "\n      " + t);
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("expectation failed: " + what);
        }
    }

    /** A child nusadb-server process on an ephemeral port. */
    static final class Server {
        final int port;
        private final Process process;
        private final File dir;

        private Server(int port, Process process, File dir) {
            this.port = port;
            this.process = process;
            this.dir = dir;
        }

        static Server start() throws Exception {
            String bin = binary();
            int port = freePort();
            File dir = Files.createTempDirectory("nusadb-hibernate6-").toFile();
            List<String> cmd = new ArrayList<>();
            cmd.add(bin);
            cmd.add("--listen");
            cmd.add("127.0.0.1:" + port);
            cmd.add("--data-dir");
            cmd.add(dir.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("RUST_LOG", "error");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            waitReady(port);
            return new Server(port, process, dir);
        }

        void stop() {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            deleteRecursively(dir);
        }

        private static String binary() {
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            String name = windows ? "nusadb-server.exe" : "nusadb-server";
            List<String> bases = new ArrayList<>();
            String env = System.getenv("CARGO_TARGET_DIR");
            if (env != null) {
                bases.add(env);
            }
            // Walk up from the working dir looking for a Cargo target directory.
            File cursor = new File(System.getProperty("user.dir"));
            for (int i = 0; i < 6 && cursor != null; i++) {
                bases.add(cursor.getAbsolutePath() + File.separator + "target");
                cursor = cursor.getParentFile();
            }
            for (String base : bases) {
                for (String profile : new String[] {"debug", "release"}) {
                    File f = new File(base + File.separator + profile + File.separator + name);
                    if (f.exists()) {
                        return f.getAbsolutePath();
                    }
                }
            }
            throw new IllegalStateException(
                    "nusadb-server binary not found; run `cargo build -p nusadb-server`");
        }

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        }

        private static void waitReady(int port) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                try (Socket s = new Socket("127.0.0.1", port)) {
                    return;
                } catch (IOException e) {
                    Thread.sleep(100);
                }
            }
            throw new IllegalStateException("server on port " + port + " did not become ready");
        }

        private static void deleteRecursively(File f) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
            f.delete();
        }
    }
}
