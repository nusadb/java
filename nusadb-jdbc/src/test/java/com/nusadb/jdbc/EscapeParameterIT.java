package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A LIKE whose ESCAPE character is a bound parameter, as Hibernate 5 criteria send it. */
class EscapeParameterIT {

    private static List<String> names(PreparedStatement ps) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void escapeBoundAsAParameter() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS esc_it");
            st.execute("CREATE TABLE esc_it (id int PRIMARY KEY, n text)");
            st.execute("INSERT INTO esc_it VALUES (1, '50% off'), (2, '50 off'), (3, 'a_b'), (4, 'axb')");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT n FROM esc_it WHERE upper(n) LIKE upper(?) ESCAPE ? AND id >= ? ORDER BY id")) {
                ps.setString(1, "%50\\%%");
                ps.setString(2, "\\");
                ps.setInt(3, 1);
                assertEquals(List.of("50% off"), names(ps));
                ps.setString(1, "%!_%");
                ps.setString(2, "!");
                ps.setInt(3, 2);
                assertEquals(List.of("a_b"), names(ps));
                ps.setString(1, "%!_%");
                ps.setString(2, "!");
                ps.setInt(3, 1);
                ps.addBatch();
                assertEquals(1, ps.executeBatch().length);
            }
            st.execute("DROP TABLE esc_it");
        }
    }
}
