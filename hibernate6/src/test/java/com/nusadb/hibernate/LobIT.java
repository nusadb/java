package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nusadb.testing.NusaTestServer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.sql.Blob;
import java.sql.Clob;
import java.util.Random;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.BlobProxy;
import org.hibernate.engine.jdbc.ClobProxy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code @Lob} attributes stored in text and bytea columns. */
class LobIT {

    @Entity(name = "Document")
    @Table(name = "lob_document")
    public static class Document {
        @Id
        Long id;

        @Lob
        String body;

        @Lob
        byte[] content;

        @Lob
        Clob clob;

        @Lob
        Blob blob;
    }

    private static final String TEXT = "lorem ipsum ".repeat(10_000);
    private static final byte[] BYTES = new byte[64 * 1024];

    static {
        new Random(3).nextBytes(BYTES);
    }

    private static SessionFactory sf;

    @BeforeAll
    static void open() {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", NusaTestServer.get().url());
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        cfg.addAnnotatedClass(Document.class);
        sf = cfg.buildSessionFactory();
    }

    @AfterAll
    static void close() {
        if (sf != null) {
            sf.close();
        }
    }

    @Test
    void lobAttributesRoundTrip() throws Exception {
        sf.inTransaction(s -> {
            Document d = new Document();
            d.id = 1L;
            d.body = TEXT;
            d.content = BYTES;
            d.clob = ClobProxy.generateProxy(TEXT);
            d.blob = BlobProxy.generateProxy(BYTES);
            s.persist(d);
        });
        sf.inTransaction(s -> {
            Document d = s.get(Document.class, 1L);
            assertEquals(TEXT, d.body);
            assertArrayEquals(BYTES, d.content);
            try {
                assertEquals(TEXT, d.clob.getSubString(1, (int) d.clob.length()));
                assertArrayEquals(BYTES, d.blob.getBytes(1, (int) d.blob.length()));
            } catch (java.sql.SQLException e) {
                throw new AssertionError(e);
            }
            d.body = "changed";
            d.content = new byte[] {1, 2, 3};
        });
        Document d = sf.fromSession(s -> s.get(Document.class, 1L));
        assertEquals("changed", d.body);
        assertArrayEquals(new byte[] {1, 2, 3}, d.content);
    }
}
