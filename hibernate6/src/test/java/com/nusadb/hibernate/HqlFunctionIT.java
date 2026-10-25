package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.nusadb.testing.NusaTestServer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** HQL functions whose SQL rendering is dialect specific, run on the server. */
class HqlFunctionIT {

    @Entity(name = "Event")
    @Table(name = "fn_event")
    public static class Event {
        @Id
        Long id;

        String name;

        String category;

        LocalDateTime at;

        LocalDate day;

        public Event() {}

        Event(Long id, String name, String category, LocalDateTime at) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.at = at;
            this.day = at.toLocalDate();
        }
    }

    private static SessionFactory sf;

    @BeforeAll
    static void open() {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.dialect", NusaDialect.class.getName());
        cfg.setProperty("hibernate.connection.driver_class", "com.nusadb.jdbc.NusaDriver");
        cfg.setProperty("hibernate.connection.url", NusaTestServer.get().url());
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        cfg.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
        cfg.addAnnotatedClass(Event.class);
        sf = cfg.buildSessionFactory();
        sf.inTransaction(s -> {
            s.persist(new Event(1L, "Alpha Beta", "x", LocalDateTime.of(2026, 3, 15, 10, 20, 30))); // a Sunday
            s.persist(new Event(2L, "b", "y", LocalDateTime.of(2026, 3, 16, 0, 0)));
            s.persist(new Event(3L, "a", "y", LocalDateTime.of(2026, 3, 17, 0, 0)));
            s.persist(new Event(4L, "c", "y", LocalDateTime.of(2026, 3, 18, 0, 0)));
        });
    }

    @AfterAll
    static void close() {
        if (sf != null) {
            sf.close();
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("trim(' x ')", "trim('  x  ')", "x"),
                Arguments.of("trim(leading)", "trim(leading 'x' from 'xxab')", "ab"),
                Arguments.of("trim(trailing)", "trim(trailing from 'ab  ')", "ab"),
                Arguments.of("locate", "locate('Beta', e.name)", 7),
                Arguments.of("locate from", "locate('a', e.name, 3)", 5),
                Arguments.of("position", "position('Beta' in e.name)", 7),
                Arguments.of("day of week", "extract(day of week from e.at)", 1),
                Arguments.of("day of month", "extract(day of month from e.at)", 15),
                Arguments.of("day of year", "extract(day of year from e.at)", 74),
                Arguments.of("week", "extract(week from e.at)", 11),
                Arguments.of("+ 1 day", "e.at + 1 day", LocalDateTime.of(2026, 3, 16, 10, 20, 30)),
                Arguments.of("+ 2 hour", "e.at + 2 hour", LocalDateTime.of(2026, 3, 15, 12, 20, 30)),
                Arguments.of("+ 1 quarter", "e.at + 1 quarter", LocalDateTime.of(2026, 6, 15, 10, 20, 30)),
                Arguments.of("date + 3 day", "e.day + 3 day", LocalDate.of(2026, 3, 18)),
                Arguments.of("diff by day", "(e.at - local datetime 2026-03-10 10:20:30) by day", 5),
                Arguments.of("diff by hour", "(e.at - local datetime 2026-03-15 08:00:00) by hour", 2),
                Arguments.of("diff by second", "(e.at - local datetime 2026-03-15 10:20:00) by second", 30),
                Arguments.of("diff by month", "(e.at - local datetime 2025-01-10 10:20:30) by month", 14),
                Arguments.of("diff by year", "(e.at - local datetime 2024-01-10 10:20:30) by year", 2),
                Arguments.of("date diff by day", "(e.day - {d '2026-03-01'}) by day", 14),
                Arguments.of("truncate month", "truncate(e.at, month)", LocalDateTime.of(2026, 3, 1, 0, 0)),
                Arguments.of("truncate number", "truncate(12.75, 1)", new BigDecimal("12.7")),
                Arguments.of("{ts} literal", "case when e.at > {ts '2026-01-01 00:00:00'} then 1 else 0 end", 1),
                Arguments.of("{d} literal", "case when e.day = {d '2026-03-15'} then 1 else 0 end", 1),
                Arguments.of("current_date", "case when current_date > {d '2020-01-01'} then 1 else 0 end", 1),
                Arguments.of("local time", "case when local time is not null then 1 else 0 end", 1),
                // arrays
                Arguments.of("array_length", "array_length(array(1, 2, 3))", 3),
                Arguments.of("array_contains", "array_contains(array(1, 2, 3), 2)", true),
                Arguments.of("array_position", "array_position(array(5, 6, 7), 6)", 2),
                Arguments.of("array_position not found", "array_position(array(5, 6, 7), 9)", 0),
                Arguments.of("array_position from", "array_position(array(1, 2, 1), 1, 2)", 3),
                Arguments.of("array_concat", "array_to_string(array_concat(array(1, 2), array(3)), ',')", "1,2,3"),
                Arguments.of("array_append", "array_to_string(array_append(array(1, 2), 3), ',')", "1,2,3"),
                Arguments.of("array_append string", "array_to_string(array_append(array('a', 'b'), 'c'), ',')", "a,b,c"),
                Arguments.of("array_append null", "array_length(array_append(array(1, 2), null))", 3),
                Arguments.of("array_prepend", "array_to_string(array_prepend(0, array(1, 2)), ',')", "0,1,2"),
                Arguments.of("array_fill", "array_to_string(array_fill('x', 3), ',')", "x,x,x"),
                Arguments.of("array_get", "array_get(array(5, 6, 7), 2)", 6),
                Arguments.of("array_to_string", "array_to_string(array('a', 'b'), ',')", "a,b"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void function(String label, String expr, Object expected) {
        Object actual = sf.fromSession(s -> s.createQuery("select " + expr + " from Event e where e.id = 1", Object.class)
                .getSingleResult());
        if (expected instanceof Number n) {
            assertInstanceOf(Number.class, actual, expr);
            assertEquals(0, new BigDecimal(n.toString()).compareTo(new BigDecimal(actual.toString())), expr + " = " + actual);
        } else if (expected instanceof LocalDate d && actual instanceof java.sql.Date sql) {
            assertEquals(d, sql.toLocalDate(), expr);
        } else if (expected instanceof LocalDateTime t && actual instanceof java.sql.Timestamp sql) {
            assertEquals(t, sql.toLocalDateTime(), expr);
        } else {
            assertEquals(expected, actual, expr);
        }
    }

    @Test
    void listaggOrdersTheAggregatedValues() {
        String joined = sf.fromSession(s -> s.createQuery(
                "select listagg(e.name, ',') within group (order by e.name) from Event e where e.category = 'y'", String.class)
                .getSingleResult());
        assertEquals("a,b,c", joined);
    }

    @Test
    void localTimeIsATime() {
        Object t = sf.fromSession(s -> s.createQuery("select local time from Event e where e.id = 1", Object.class).getSingleResult());
        assertInstanceOf(LocalTime.class, t instanceof java.sql.Time sql ? sql.toLocalTime() : t);
    }
}
