package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** CLOB and BLOB values and the stream setters and getters, over text and bytea columns. */
class LobIT {

    private static final String TEXT = "Ünïcödé line\n".repeat(10_000);
    private static final byte[] BYTES = new byte[64 * 1024];

    static {
        new Random(7).nextBytes(BYTES);
    }

    private static void table(Statement st) throws SQLException {
        st.execute("DROP TABLE IF EXISTS lob_t");
        st.execute("CREATE TABLE lob_t (id INT PRIMARY KEY, c TEXT, b BYTEA)");
    }

    @Test
    void clobAndBlobRoundTrip() throws SQLException, IOException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            table(st);
            Clob clob = c.createClob();
            clob.setString(1, TEXT);
            Blob blob = c.createBlob();
            blob.setBytes(1, BYTES);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO lob_t VALUES (?, ?, ?)")) {
                ps.setInt(1, 1);
                ps.setClob(2, clob);
                ps.setBlob(3, blob);
                ps.executeUpdate();
                ps.setInt(1, 2);
                ps.setObject(2, clob);
                ps.setObject(3, blob);
                ps.executeUpdate();
                ps.setInt(1, 3);
                ps.setClob(2, (Clob) null);
                ps.setBlob(3, (Blob) null);
                ps.executeUpdate();
            }
            try (ResultSet rs = st.executeQuery("SELECT c, b FROM lob_t ORDER BY id")) {
                for (int i = 0; i < 2; i++) {
                    assertTrue(rs.next());
                    Clob gotClob = rs.getClob(1);
                    assertEquals(TEXT.length(), gotClob.length());
                    assertEquals(TEXT, gotClob.getSubString(1, (int) gotClob.length()));
                    assertEquals("Ünïcödé", gotClob.getSubString(1, 7));
                    assertEquals(9, gotClob.position("line", 1));
                    Blob gotBlob = rs.getBlob("b");
                    assertEquals(BYTES.length, gotBlob.length());
                    assertArrayEquals(BYTES, gotBlob.getBinaryStream().readAllBytes());
                    assertEquals(TEXT, read(rs.getCharacterStream("c")));
                    assertArrayEquals(BYTES, rs.getBinaryStream(2).readAllBytes());
                    assertEquals(TEXT, rs.getObject(1, Clob.class).getSubString(1, TEXT.length()));
                }
                assertTrue(rs.next());
                assertNull(rs.getClob(1));
                assertTrue(rs.wasNull());
                assertNull(rs.getBlob(2));
                assertNull(rs.getCharacterStream(1));
                assertNull(rs.getBinaryStream(2));
            }
            st.execute("DROP TABLE lob_t");
        }
    }

    @Test
    void streamsAreReadUpToTheirLength() throws SQLException, IOException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            table(st);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO lob_t VALUES (?, ?, ?)")) {
                ps.setInt(1, 1);
                ps.setCharacterStream(2, new StringReader(TEXT), TEXT.length());
                ps.setBinaryStream(3, new ByteArrayInputStream(BYTES), BYTES.length);
                ps.executeUpdate();
                ps.setInt(1, 2);
                ps.setClob(2, new StringReader("abcdef"), 3);
                ps.setBlob(3, new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 2L);
                ps.executeUpdate();
                ps.setInt(1, 3);
                ps.setNCharacterStream(2, new StringReader("n"));
                ps.setBinaryStream(3, (InputStream) null);
                ps.executeUpdate();
                ps.setInt(1, 4);
                ps.setAsciiStream(2, new ByteArrayInputStream("ascii".getBytes()));
                ps.setBlob(3, new ByteArrayInputStream(new byte[0]));
                ps.executeUpdate();
            }
            try (ResultSet rs = st.executeQuery("SELECT c, b FROM lob_t ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(TEXT, rs.getString(1));
                assertArrayEquals(BYTES, rs.getBytes(2));
                assertTrue(rs.next());
                assertEquals("abc", rs.getString(1));
                assertArrayEquals(new byte[] {1, 2}, rs.getBytes(2));
                assertTrue(rs.next());
                assertEquals("n", rs.getNString(1));
                assertNull(rs.getBytes(2));
                assertTrue(rs.next());
                assertEquals("ascii", new String(rs.getAsciiStream(1).readAllBytes()));
                assertArrayEquals(new byte[0], rs.getBytes(2));
            }
            st.execute("DROP TABLE lob_t");
        }
    }

    @Test
    void clientSideLobsCanBeEdited() throws SQLException, IOException {
        try (Connection c = NusaTestServer.get().connect()) {
            Clob clob = c.createClob();
            try (Writer w = clob.setCharacterStream(1)) {
                w.write("hello world");
            }
            clob.setString(7, "there");
            assertEquals("hello there", clob.getSubString(1, 100));
            clob.truncate(5);
            assertEquals(5, clob.length());
            Blob blob = c.createBlob();
            blob.setBytes(1, new byte[] {1, 2, 3});
            blob.setBytes(3, new byte[] {9, 9});
            assertArrayEquals(new byte[] {1, 2, 9, 9}, blob.getBytes(1, 10));
            assertEquals(3, blob.position(new byte[] {9}, 1));
            blob.free();
            assertThrows(SQLException.class, blob::length);
        }
    }

    private static String read(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
