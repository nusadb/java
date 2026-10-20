package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.nusadb.testing.NusaTestServer;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;

/** Hibernate boots with only the URL: the dialect is resolved from the connection's metadata. */
class DialectResolutionIT {

    @Entity(name = "Resolved")
    @Table(name = "resolved_thing")
    public static class Thing {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
        Long id;

        String name;
    }

    @Test
    void theDialectIsPickedFromTheConnection() {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.connection.url", NusaTestServer.get().url());
        cfg.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        cfg.setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        cfg.addAnnotatedClass(Thing.class);
        try (SessionFactory sf = cfg.buildSessionFactory()) {
            assertInstanceOf(NusaDialect.class, sf.unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect());
            sf.inTransaction(s -> {
                for (int i = 0; i < 3; i++) {
                    Thing t = new Thing();
                    t.name = "t" + i;
                    s.persist(t);
                }
            });
            Long n = sf.fromSession(s -> s.createQuery("select count(*) from Resolved", Long.class).getSingleResult());
            assertEquals(3L, n);
        }
    }
}
