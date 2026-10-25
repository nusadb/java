package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nusadb.testing.NusaTestServer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/** Columns and tables named after words the server reserves (key, index, interval, replace). */
class ReservedNameIT {

    @Entity(name = "Replace")
    @Table(name = "replace")
    public static class Replace {
        @Id
        Long id;

        String key;

        int index;

        String interval;

        public Replace() {}

        Replace(Long id, String key, int index, String interval) {
            this.id = id;
            this.key = key;
            this.index = index;
            this.interval = interval;
        }
    }

    @Test
    void entityWithReservedNamesRoundTrips() {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", NusaTestServer.get().url());
        cfg.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        cfg.addAnnotatedClass(Replace.class);
        try (SessionFactory sf = cfg.buildSessionFactory()) {
            sf.inTransaction(s -> s.persist(new Replace(1L, "theme", 3, "daily")));
            sf.inTransaction(s -> s.createMutationQuery("update Replace r set r.index = 4 where r.key = 'theme'")
                    .executeUpdate());
            Replace r = sf.fromSession(s -> s.createSelectionQuery(
                    "from Replace r where r.interval = 'daily'", Replace.class).getSingleResult());
            assertEquals(4, r.index);
            assertEquals("theme", r.key);
        }
    }
}
