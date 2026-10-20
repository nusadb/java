package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nusadb.testing.NusaTestServer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HQL UPDATE and DELETE whose restriction navigates to another entity (an implicit join). */
class BulkMutationIT {

    @Entity(name = "BulkCategory")
    @Table(name = "bulk_category")
    public static class Category {
        @Id
        Long id;

        String name;
    }

    @Entity(name = "BulkProduct")
    @Table(name = "bulk_product")
    public static class Product {
        @Id
        Long id;

        BigDecimal price;

        @ManyToOne
        Category category;
    }

    private static SessionFactory sf;

    @BeforeAll
    static void open() {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", NusaTestServer.get().url());
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        cfg.addAnnotatedClass(Category.class);
        cfg.addAnnotatedClass(Product.class);
        sf = cfg.buildSessionFactory();
    }

    @AfterAll
    static void close() {
        if (sf != null) {
            sf.close();
        }
    }

    @BeforeEach
    void seed() {
        sf.inTransaction(s -> {
            s.createMutationQuery("delete from BulkProduct").executeUpdate();
            s.createMutationQuery("delete from BulkCategory").executeUpdate();
            Category games = category(1L, "Games");
            Category books = category(2L, "Books");
            s.persist(games);
            s.persist(books);
            long id = 1;
            for (Category c : List.of(games, games, games, books)) {
                Product p = new Product();
                p.id = id++;
                p.price = new BigDecimal("20.00");
                p.category = c;
                s.persist(p);
            }
        });
    }

    private static Category category(Long id, String name) {
        Category c = new Category();
        c.id = id;
        c.name = name;
        return c;
    }

    private static BigDecimal total(String category) {
        return sf.fromSession(s -> s.createQuery(
                "select sum(p.price) from BulkProduct p where p.category.name = :c", BigDecimal.class)
                .setParameter("c", category).getSingleResult());
    }

    @Test
    void updateThroughAnImplicitJoin() {
        int n = sf.fromTransaction(s -> s.createMutationQuery(
                "update BulkProduct p set p.price = p.price * :f where p.category.name = :c")
                .setParameter("f", new BigDecimal("2")).setParameter("c", "Games").executeUpdate());
        assertEquals(3, n);
        assertEquals(0, new BigDecimal("120.00").compareTo(total("Games")));
        assertEquals(0, new BigDecimal("20.00").compareTo(total("Books")));
    }

    @Test
    void deleteThroughAnImplicitJoin() {
        int n = sf.fromTransaction(s -> s.createMutationQuery("delete from BulkProduct p where p.category.name = :c")
                .setParameter("c", "Books").executeUpdate());
        assertEquals(1, n);
        Long left = sf.fromSession(s -> s.createQuery("select count(*) from BulkProduct", Long.class).getSingleResult());
        assertEquals(3L, left);
    }

    @Test
    void updateWithoutAJoinStaysPlain() {
        int n = sf.fromTransaction(s -> s.createMutationQuery("update BulkProduct p set p.price = 1 where p.id > 2")
                .executeUpdate());
        assertEquals(2, n);
    }
}
