package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Function and keyword lists, getTypeInfo and getBestRowIdentifier. */
class MetadataListsIT {

    @Test
    void everyListedFunctionNameIsAnEscape() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            DatabaseMetaData md = c.getMetaData();
            assertTrue(md.getNumericFunctions().contains("sqrt"));
            assertTrue(md.getTimeDateFunctions().contains("timestampadd"));
            assertTrue(md.getSQLKeywords().contains("key"));
            try (ResultSet rs = st.executeQuery("SELECT {fn mod(7, 3)}, {fn ucase('a')}, {fn ifnull(null, 1)}")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void typeInfoIsOrderedAndEveryNameCreatesAColumn() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            List<String> names = new ArrayList<>();
            int last = Integer.MIN_VALUE;
            try (ResultSet rs = c.getMetaData().getTypeInfo()) {
                while (rs.next()) {
                    int type = rs.getInt("DATA_TYPE");
                    assertTrue(type >= last, "ordered by DATA_TYPE");
                    last = type;
                    names.add(rs.getString("TYPE_NAME"));
                }
            }
            int n = 0;
            for (String name : names) {
                st.execute("CREATE TEMPORARY TABLE type_info_" + (n++) + " (x " + name + ")");
            }
        }
    }

    @Test
    void bestRowIdentifierIsThePrimaryKey() throws SQLException {
        try (Connection c = NusaTestServer.get().connect(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS best_row_it");
            st.execute("DROP TABLE IF EXISTS best_row_none_it");
            st.execute("CREATE TABLE best_row_it (a int, b text, v text, PRIMARY KEY (a, b))");
            st.execute("CREATE TABLE best_row_none_it (v text)");
            List<String> cols = new ArrayList<>();
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getBestRowIdentifier(null, null, "best_row_it", DatabaseMetaData.bestRowSession, true)) {
                while (rs.next()) {
                    cols.add(rs.getString("COLUMN_NAME"));
                }
            }
            cols.sort(null);
            assertEquals(List.of("a", "b"), cols);
            try (ResultSet rs = md.getBestRowIdentifier(null, null, "best_row_none_it", DatabaseMetaData.bestRowSession, true)) {
                assertFalse(rs.next());
            }
        }
    }
}
