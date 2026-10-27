package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Id generators, the uuid type, reserved names and the HQL functions the dialect renders. */
class DialectFeaturesIT {

    @Entity(name = "Ident")
    @Table(name = "h5_ident")
    public static class Ident {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        String name;
    }

    @Entity(name = "Pooled")
    @Table(name = "h5_pooled")
    public static class Pooled {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "h5_pooled_seq")
        @SequenceGenerator(name = "h5_pooled_seq", sequenceName = "H5_Pooled_Seq", allocationSize = 5)
        Long id;
        String name;
    }

    @Entity(name = "Uuid")
    @Table(name = "h5_uuid")
    public static class Uuid {
        @Id
        Long id;
        UUID token;
    }

    @Entity(name = "Replace")
    @Table(name = "replace")
    public static class Replace {
        @Id
        Long id;
        String key;
        int index;
        String interval;
    }

    private static SessionFactory sf;

    @BeforeAll
    static void open() {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", NusaTestServer.get().url());
        cfg.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        cfg.addAnnotatedClass(Ident.class);
        cfg.addAnnotatedClass(Pooled.class);
        cfg.addAnnotatedClass(Uuid.class);
        cfg.addAnnotatedClass(Replace.class);
        sf = cfg.buildSessionFactory();
    }

    @AfterAll
    static void close() {
        if (sf != null) {
            sf.close();
        }
    }

    private static void tx(java.util.function.Consumer<Session> work) {
        try (Session s = sf.openSession()) {
            s.beginTransaction();
            work.accept(s);
            s.getTransaction().commit();
        }
    }

    @Test
    void identityAndPooledSequenceIds() {
        Set<Long> ids = new HashSet<>();
        tx(s -> {
            for (int i = 0; i < 12; i++) {
                Ident a = new Ident();
                s.persist(a);
                ids.add(a.id);
                Pooled p = new Pooled();
                s.persist(p);
                ids.add(-p.id);
            }
        });
        assertEquals(24, ids.size());
        assertTrue(ids.stream().allMatch(java.util.Objects::nonNull));
    }

    @Test
    void uuidIsANativeColumn() throws Exception {
        UUID token = UUID.randomUUID();
        tx(s -> {
            Uuid u = new Uuid();
            u.id = 1L;
            u.token = token;
            s.persist(u);
        });
        try (Session s = sf.openSession()) {
            assertEquals(token, s.get(Uuid.class, 1L).token);
            assertEquals(1L, s.createQuery("select u.id from Uuid u where u.token = :t", Long.class)
                    .setParameter("t", token).getSingleResult());
        }
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'h5_uuid' AND column_name = 'token'")) {
            assertTrue(rs.next());
            assertEquals("uuid", rs.getString(1).toLowerCase());
        }
    }

    @Test
    void reservedNamesAndFunctions() {
        tx(s -> {
            Replace r = new Replace();
            r.id = 1L;
            r.key = "  Theme Park  ";
            r.index = 3;
            r.interval = "daily";
            s.persist(r);
        });
        try (Session s = sf.openSession()) {
            List<?> row = List.of((Object[]) s.createQuery(
                    "select locate('Park', r.key), locate('e', r.key, 6), trim(r.key), trim(leading ' ' from r.key), "
                            + "str(r.index), concat(r.interval, '!'), current_date, upper(r.interval) "
                            + "from Replace r where r.index = 3 and r.key like '%Park%'").getSingleResult());
            assertEquals(9, ((Number) row.get(0)).intValue());
            assertEquals(7, ((Number) row.get(1)).intValue());
            assertEquals("Theme Park", row.get(2));
            assertEquals("Theme Park  ", row.get(3));
            assertEquals("3", row.get(4));
            assertEquals("daily!", row.get(5));
            assertNotNull(row.get(6));
            assertEquals("DAILY", row.get(7));
            assertEquals(1, s.createQuery("from Replace r order by r.interval").setMaxResults(1).setFirstResult(0)
                    .getResultList().size());
        }
    }
}
