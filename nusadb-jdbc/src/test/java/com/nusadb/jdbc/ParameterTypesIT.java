package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parameters are declared with their JDBC type, so the server does not guess one from the text:
 * a numeric-looking string stays a string and a decimal keeps every digit.
 */
class ParameterTypesIT {
    private static final String[] LOOKS_LIKE_OTHER_TYPES = {
        "00123", "081234567890123456", "1e5", "true", "f", "12345678901234567890", "0.10", "NaN"};

    private static Connection open() throws SQLException {
        return NusaTestServer.get().connect();
    }

    @BeforeEach
    void tables() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS pt_text");
            st.execute("CREATE TABLE pt_text (id INT PRIMARY KEY, t TEXT, v VARCHAR(40))");
            st.execute("DROP TABLE IF EXISTS pt_num");
            st.execute("CREATE TABLE pt_num (id INT PRIMARY KEY, d NUMERIC(38,10), n NUMERIC(38,0), i BIGINT, b BOOLEAN)");
        }
    }

    @Test
    void stringsThatLookLikeNumbersOrBooleansStayStrings() throws SQLException {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pt_text VALUES (?, ?, ?)")) {
                int id = 0;
                for (String v : LOOKS_LIKE_OTHER_TYPES) {
                    ps.setInt(1, ++id);
                    ps.setString(2, v);
                    ps.setObject(3, v);
                    ps.executeUpdate();
                }
            }
            List<String> t = new ArrayList<>();
            List<String> v = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT t, v FROM pt_text ORDER BY id")) {
                while (rs.next()) {
                    t.add(rs.getString(1));
                    v.add(rs.getString(2));
                }
            }
            assertEquals(List.of(LOOKS_LIKE_OTHER_TYPES), t);
            assertEquals(List.of(LOOKS_LIKE_OTHER_TYPES), v);
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pt_text WHERE t = ?")) {
                ps.setString(1, "00123");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT CAST(? AS TEXT)")) {
                ps.setString(1, "123456789012345.6789");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals("123456789012345.6789", rs.getString(1));
                }
            }
        }
    }

    @Test
    void decimalsKeepEveryDigit() throws SQLException {
        BigDecimal d = new BigDecimal("123456789012345678.1234567891");
        BigInteger n = new BigInteger("12345678901234567890123456789");
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pt_num (id, d, n) VALUES (?, ?, ?)")) {
                ps.setInt(1, 1);
                ps.setBigDecimal(2, d);
                ps.setObject(3, n);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT d, n FROM pt_num WHERE id = 1")) {
                rs.next();
                assertEquals(0, d.compareTo(rs.getBigDecimal(1)));
                assertEquals(0, new BigDecimal(n).compareTo(rs.getBigDecimal(2)));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pt_num WHERE d = ?")) {
                ps.setBigDecimal(1, d);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void numbersAndBooleansStillBindIntoTheirColumns() throws SQLException {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pt_num (id, i, b, d) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, 2);
                ps.setObject(2, 9_000_000_000L);
                ps.setBoolean(3, true);
                ps.setDouble(4, 1.5);
                ps.executeUpdate();
                ps.setObject(1, "3", Types.INTEGER);
                ps.setObject(2, "42", Types.BIGINT);
                ps.setObject(3, "false", Types.BOOLEAN);
                ps.setObject(4, "2.25", Types.NUMERIC);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT i, b, d FROM pt_num WHERE id IN (2, 3) ORDER BY id")) {
                rs.next();
                assertEquals(9_000_000_000L, rs.getLong(1));
                assertTrue(rs.getBoolean(2));
                assertEquals(0, new BigDecimal("1.5").compareTo(rs.getBigDecimal(3)));
                rs.next();
                assertEquals(42L, rs.getLong(1));
                assertEquals(false, rs.getBoolean(2));
                assertEquals(0, new BigDecimal("2.25").compareTo(rs.getBigDecimal(3)));
            }
        }
    }

    @Test
    void oneStatementServesDifferentTypeCombinations() throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT ?")) {
            ps.setString(1, "007");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("007", rs.getString(1));
            }
            ps.setInt(1, 7);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(7, rs.getInt(1));
            }
            ps.setNull(1, Types.VARCHAR);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(null, rs.getString(1));
            }
        }
    }

    @Test
    void batchesDeclareTypesWithAndWithoutRewrite() throws SQLException {
        for (boolean rewrite : new boolean[] {false, true}) {
            tables();
            Properties p = new Properties();
            p.setProperty("rewriteBatchedInserts", Boolean.toString(rewrite));
            try (Connection c = DriverManager.getConnection(NusaTestServer.get().url(), p)) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO pt_text (id, t, v) VALUES (?, ?, ?)")) {
                    int id = 0;
                    for (String v : LOOKS_LIKE_OTHER_TYPES) {
                        ps.setInt(1, ++id);
                        ps.setString(2, v);
                        if (id % 2 == 0) {
                            ps.setNull(3, Types.VARCHAR);
                        } else {
                            ps.setString(3, v);
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT t FROM pt_text ORDER BY id")) {
                    List<String> t = new ArrayList<>();
                    while (rs.next()) {
                        t.add(rs.getString(1));
                    }
                    assertEquals(List.of(LOOKS_LIKE_OTHER_TYPES), t, "rewrite=" + rewrite);
                }
            }
        }
    }

    @Test
    void uuidAndBytesCompareWithTheirColumns() throws SQLException {
        UUID u = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
        byte[] bytes = {0, 1, 2, (byte) 0xff};
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS pt_misc");
            st.execute("CREATE TABLE pt_misc (id UUID PRIMARY KEY, b BYTEA)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pt_misc VALUES (?, ?)")) {
                ps.setObject(1, u);
                ps.setBytes(2, bytes);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT b FROM pt_misc WHERE id = ? AND b = ?")) {
                ps.setObject(1, u);
                ps.setBytes(2, bytes);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertArrayEquals(bytes, rs.getBytes(1));
                }
            }
        }
    }

    @Test
    void stringTypeUnspecifiedKeepsTheOldInference() throws SQLException {
        Properties p = new Properties();
        p.setProperty("stringType", "unspecified");
        try (Connection c = DriverManager.getConnection(NusaTestServer.get().url(), p);
             PreparedStatement ps = c.prepareStatement("SELECT ? + 1")) {
            ps.setString(1, "41");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(42, rs.getInt(1));
            }
        }
        Properties bad = new Properties();
        bad.setProperty("stringType", "nonsense");
        assertThrows(SQLException.class, () -> DriverManager.getConnection(NusaTestServer.get().url(), bad).close());
    }
}
