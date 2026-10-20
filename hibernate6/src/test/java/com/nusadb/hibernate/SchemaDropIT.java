package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.nusadb.testing.NusaTestServer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/** {@code create} and {@code create-drop} with halt_on_error, starting where the tables do not exist. */
class SchemaDropIT {

    @Entity(name = "DropParent")
    @Table(name = "drop_parent")
    public static class Parent {
        @Id
        Long id;
    }

    @Entity(name = "DropChild")
    @Table(name = "drop_child")
    public static class Child {
        @Id
        Long id;

        @ManyToOne
        Parent parent;
    }

    private static SessionFactory build(String auto) {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", NusaTestServer.get().url());
        cfg.setProperty("hibernate.hbm2ddl.auto", auto);
        cfg.setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        cfg.addAnnotatedClass(Parent.class);
        cfg.addAnnotatedClass(Child.class);
        return cfg.buildSessionFactory();
    }

    private static boolean exists(String table) throws SQLException {
        try (Connection c = NusaTestServer.get().connect();
                ResultSet rs = c.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    @Test
    void createDropRunsOnAFreshDatabase() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS drop_child CASCADE");
            st.execute("DROP TABLE IF EXISTS drop_parent CASCADE");
        }
        try (SessionFactory sf = build("create-drop")) {
            sf.inTransaction(s -> {
                Parent p = new Parent();
                p.id = 1L;
                s.persist(p);
                Child ch = new Child();
                ch.id = 1L;
                ch.parent = p;
                s.persist(ch);
            });
            // create again over the existing, related tables: the drop must get past the foreign key
            build("create").close();
            Long rows = sf.fromSession(s -> s.createQuery("select count(*) from DropChild", Long.class).getSingleResult());
            assertEquals(0L, rows);
        }
        assertFalse(exists("drop_child"), "create-drop dropped the tables on close");
        assertFalse(exists("drop_parent"));
    }
}
