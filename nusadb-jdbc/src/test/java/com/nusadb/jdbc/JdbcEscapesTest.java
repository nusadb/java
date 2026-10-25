package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class JdbcEscapesTest {

    private static String t(String sql) throws SQLException {
        return JdbcEscapes.translate(sql);
    }

    @Test
    void literals() throws SQLException {
        assertEquals("SELECT DATE '2026-03-15', TIME '10:20:30', TIMESTAMP '2026-03-15 10:20:30.5'",
                t("SELECT {d '2026-03-15'}, {t '10:20:30'}, {ts '2026-03-15 10:20:30.5'}"));
        assertEquals("SELECT DATE '2026-03-15'", t("SELECT { D '2026-03-15' }"));
    }

    @Test
    void functionsAreMappedAndNested() throws SQLException {
        assertEquals("SELECT upper(lower('a'))", t("SELECT {fn ucase({fn lcase('a')})}"));
        assertEquals("SELECT ('a' || 'b'), ln(2), ceil(1.5), trunc(1.25, 1)",
                t("SELECT {fn concat('a', 'b')}, {fn log(2)}, {fn ceiling(1.5)}, {FN TRUNCATE(1.25, 1)}"));
        assertEquals("SELECT overlay('abcdef' placing 'X' from 2 for 3)", t("SELECT {fn insert('abcdef', 2, 3, 'X')}"));
        assertEquals("SELECT (extract(dow from DATE '2026-03-15') + 1)", t("SELECT {fn dayofweek({d '2026-03-15'})}"));
        assertEquals("SELECT (TIMESTAMP '2026-03-15 10:00:00' + (2) * interval '1 day')",
                t("SELECT {fn timestampadd(SQL_TSI_DAY, 2, {ts '2026-03-15 10:00:00'})}"));
        assertEquals("SELECT coalesce(a, 'x,y'), pi(), current_user", t("SELECT {fn ifnull(a, 'x,y')}, {fn pi()}, {fn user()}"));
        assertEquals("SELECT abs(-1)", t("SELECT {fn abs(-1)}"));
    }

    @Test
    void otherEscapes() throws SQLException {
        assertEquals("SELECT * FROM a LEFT OUTER JOIN b ON b.id = a.id", t("SELECT * FROM {oj a LEFT OUTER JOIN b ON b.id = a.id}"));
        assertEquals("SELECT 1 WHERE x LIKE 'a!%' ESCAPE '!'", t("SELECT 1 WHERE x LIKE 'a!%' {escape '!'}"));
        assertEquals("SELECT 1 LIMIT 5 OFFSET 10", t("SELECT 1 {limit 5 offset 10}"));
        assertEquals("{call f(?)}", t("{call f(?)}"), "call escapes are left alone");
    }

    @Test
    void literalsCommentsAndIdentifiersAreNotTouched() throws SQLException {
        String sql = "SELECT '{fn ucase(''a'')}', \"{d}\" -- {fn x()}\n, /* {ts 'x'} */ 1";
        assertEquals(sql, t(sql));
        String plain = "SELECT 1";
        assertSame(plain, t(plain), "no escape, no copy");
    }

    @Test
    void parametersKeepTheirOrder() throws SQLException {
        assertEquals("SELECT (? || ?) WHERE d < DATE '2026-03-15' AND upper(?) = ?",
                t("SELECT {fn concat(?, ?)} WHERE d < {d '2026-03-15'} AND {fn ucase(?)} = ?"));
    }

    @Test
    void badEscapesAreReported() {
        assertEquals("42601", assertThrows(SQLException.class, () -> t("SELECT {fn ucase('a')")).getSQLState());
        assertEquals("42601", assertThrows(SQLException.class, () -> t("SELECT {fn concat('a')}")).getSQLState());
        assertEquals("0A000", assertThrows(SQLException.class, () -> t("SELECT {fn locate('a', 'b', 2)}")).getSQLState());
    }
}
