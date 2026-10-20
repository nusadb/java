package com.nusadb.hibernate;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The constraint a NusaDB integrity error names. The server puts it in the message:
 * {@code violates unique constraint "uk_sku"}, {@code violates foreign key "fk_order"} or
 * {@code violates unique index ux_name}.
 */
final class NusaConstraintNames {

    private static final Pattern NAME =
            Pattern.compile("violates (?:[a-z ]*?)(?:constraint|foreign key|index) (?:\"([^\"]+)\"|([^\\s:\"]+))");

    private NusaConstraintNames() {}

    /** The constraint name in an integrity violation (SQLState class 23), or null. */
    static String of(SQLException e) {
        for (SQLException x = e; x != null; x = x.getNextException()) {
            String state = x.getSQLState();
            if (state != null && state.startsWith("23") && x.getMessage() != null) {
                Matcher m = NAME.matcher(x.getMessage());
                if (m.find()) {
                    return m.group(1) != null ? m.group(1) : m.group(2);
                }
            }
        }
        return null;
    }
}
