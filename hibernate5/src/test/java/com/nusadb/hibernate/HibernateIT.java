package com.nusadb.hibernate;

import com.nusadb.testing.NusaTestServer;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration test for the NusaDB Hibernate 5 dialect against a real server ({@link NusaTestServer}).
 * Builds a {@link SessionFactory} over the JDBC driver and exercises schema export, CRUD, a query
 * with a row limit, and transaction rollback.
 *
 * <p>The steps build on each other (persist, then update, ...), so they run in a fixed order. Run with
 * {@code mvn verify -Pit}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HibernateIT {

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

    private static SessionFactory sf;

    @BeforeAll
    static void openSessionFactory() {
        sf = buildSessionFactory(NusaTestServer.get());
    }

    @AfterAll
    static void closeSessionFactory() {
        if (sf != null) {
            sf.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("schema export + persist + load")
    void persistAndLoad() {
        persistAndLoad(sf);
    }

    @Test
    @Order(2)
    @DisplayName("update")
    void update() {
        update(sf);
    }

    @Test
    @Order(3)
    @DisplayName("ordered query with limit")
    void orderedLimit() {
        orderedLimit(sf);
    }

    @Test
    @Order(4)
    @DisplayName("delete")
    void delete() {
        delete(sf);
    }

    @Test
    @Order(5)
    @DisplayName("transaction rollback discards")
    void rollback() {
        rollback(sf);
    }

    private static SessionFactory buildSessionFactory(NusaTestServer server) {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", server.url());
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        cfg.setProperty("hibernate.temp.use_jdbc_metadata_defaults", "false");
        cfg.setProperty("hibernate.show_sql", "false");
        cfg.addAnnotatedClass(Account.class);
        return cfg.buildSessionFactory();
    }

    private static void persistAndLoad(SessionFactory sf) {
        inTransaction(sf, session -> session.persist(new Account(1L, "alice", 100)));
        try (Session session = sf.openSession()) {
            Account a = session.get(Account.class, 1L);
            expect(a != null, "row loaded");
            expect("alice".equals(a.getName()), "name == alice");
            expect(a.getBalance() == 100, "balance == 100");
        }
    }

    private static void update(SessionFactory sf) {
        inTransaction(sf, session -> {
            Account a = session.get(Account.class, 1L);
            a.setBalance(250);
        });
        try (Session session = sf.openSession()) {
            Account a = session.get(Account.class, 1L);
            expect(a.getBalance() == 250, "balance updated to 250");
        }
    }

    private static void orderedLimit(SessionFactory sf) {
        inTransaction(sf, session -> {
            session.persist(new Account(2L, "bob", 50));
            session.persist(new Account(3L, "carol", 75));
        });
        try (Session session = sf.openSession()) {
            List<Account> top = session
                    .createQuery("from Account order by balance desc", Account.class)
                    .setMaxResults(1)
                    .list();
            expect(top.size() == 1, "limit 1 returns one row");
            expect("alice".equals(top.get(0).getName()), "highest balance is alice (250)");
        }
    }

    private static void delete(SessionFactory sf) {
        inTransaction(sf, session -> {
            Account a = session.get(Account.class, 2L);
            session.delete(a);
        });
        try (Session session = sf.openSession()) {
            expect(session.get(Account.class, 2L) == null, "bob deleted");
            expect(session.get(Account.class, 3L) != null, "carol survives");
        }
    }

    private static void rollback(SessionFactory sf) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            session.persist(new Account(99L, "ghost", 0));
            session.flush();
            tx.rollback();
        }
        try (Session session = sf.openSession()) {
            expect(session.get(Account.class, 99L) == null, "rolled-back row absent");
        }
    }

    /** Runs a unit of work inside a committed transaction (Hibernate 5.6 has no SessionFactory#inTransaction). */
    private static void inTransaction(SessionFactory sf, java.util.function.Consumer<Session> work) {
        try (Session session = sf.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                work.accept(session);
                tx.commit();
            } catch (RuntimeException e) {
                tx.rollback();
                throw e;
            }
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("expectation failed: " + what);
        }
    }
}
