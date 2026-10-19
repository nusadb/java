package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code @GeneratedValue} with IDENTITY, SEQUENCE (pooled) and AUTO, and schema validate/update over them. */
class GeneratedIdIT {

    @Entity(name = "IdentityThing")
    @Table(name = "gen_identity")
    public static class IdentityThing {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        String name;

        public IdentityThing() {}

        IdentityThing(String name) {
            this.name = name;
        }
    }

    @Entity(name = "IdentityOnly")
    @Table(name = "gen_identity_only")
    public static class IdentityOnly {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Integer id;
    }

    @Entity(name = "SequenceThing")
    @Table(name = "gen_sequence")
    public static class SequenceThing {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen_sequence_ids")
        @SequenceGenerator(name = "gen_sequence_ids", sequenceName = "gen_sequence_ids", allocationSize = 50)
        Long id;

        String name;

        public SequenceThing() {}

        SequenceThing(String name) {
            this.name = name;
        }
    }

    @Entity(name = "AutoThing")
    @Table(name = "gen_auto")
    public static class AutoThing {
        @Id
        @GeneratedValue
        Long id;

        String name;

        public AutoThing() {}

        AutoThing(String name) {
            this.name = name;
        }
    }

    private static SessionFactory sf;

    @BeforeAll
    static void open() {
        sf = build("create");
    }

    @AfterAll
    static void close() {
        if (sf != null) {
            sf.close();
        }
    }

    private static SessionFactory build(String hbm2ddl) {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", NusaTestServer.get().url());
        cfg.setProperty("hibernate.hbm2ddl.auto", hbm2ddl);
        cfg.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
        if (!"create".equals(hbm2ddl)) {
            cfg.setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        }
        cfg.addAnnotatedClass(IdentityThing.class);
        cfg.addAnnotatedClass(IdentityOnly.class);
        cfg.addAnnotatedClass(SequenceThing.class);
        cfg.addAnnotatedClass(AutoThing.class);
        return cfg.buildSessionFactory();
    }

    @Test
    void identityKeysComeBackFromTheInsert() {
        List<IdentityThing> things = new ArrayList<>();
        sf.inTransaction(s -> {
            for (int i = 0; i < 5; i++) {
                IdentityThing t = new IdentityThing("t" + i);
                s.persist(t);
                assertNotNull(t.id, "the id is known right after persist");
                things.add(t);
            }
        });
        for (int i = 1; i < things.size(); i++) {
            assertTrue(things.get(i).id > things.get(i - 1).id, "identity values increase");
        }
        sf.inSession(s -> assertEquals("t3", s.get(IdentityThing.class, things.get(3).id).name));
    }

    @Test
    void anEntityWithOnlyAnIdentityColumnInsertsDefaultValues() {
        IdentityOnly a = new IdentityOnly();
        IdentityOnly b = new IdentityOnly();
        sf.inTransaction(s -> {
            s.persist(a);
            s.persist(b);
        });
        assertNotNull(a.id);
        assertTrue(b.id > a.id);
    }

    @Test
    void pooledSequenceHandsOutUniqueIdsAcrossBlocks() throws SQLException {
        Set<Long> ids = new HashSet<>();
        for (int round = 0; round < 3; round++) {
            sf.inTransaction(s -> {
                for (int i = 0; i < 40; i++) {
                    SequenceThing t = new SequenceThing("s" + i);
                    s.persist(t);
                    ids.add(t.id);
                }
            });
        }
        assertEquals(120, ids.size(), "120 persisted rows, 120 distinct ids");
        assertEquals("50", scalar("SELECT increment FROM information_schema.sequences WHERE sequence_name = 'gen_sequence_ids'"),
                "the sequence steps by the allocation size");
        assertEquals("120", scalar("SELECT count(*) FROM gen_sequence"));
    }

    @Test
    void autoUsesANativeSequence() throws SQLException {
        AutoThing t = new AutoThing("auto");
        sf.inTransaction(s -> s.persist(t));
        assertNotNull(t.id);
        assertEquals("1", scalar("SELECT count(*) FROM information_schema.sequences WHERE sequence_name = 'gen_auto_seq'"));
        sf.inSession(s -> assertEquals("auto", s.get(AutoThing.class, t.id).name));
    }

    @Test
    void validateAndUpdateAcceptTheGeneratedSchema() {
        // halt_on_error: a missing sequence, or an update that tries to create one again, fails here.
        build("validate").close();
        build("update").close();
        AutoThing t = new AutoThing("after update");
        sf.inTransaction(s -> s.persist(t));
        assertNotNull(t.id);
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
