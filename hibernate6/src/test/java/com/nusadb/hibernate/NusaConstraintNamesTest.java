package com.nusadb.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class NusaConstraintNamesTest {

    private static String name(String state, String message) {
        return NusaConstraintNames.of(new SQLException("nusadb: constraint violation: " + message, state));
    }

    @Test
    void theNameComesFromTheServerMessage() {
        assertEquals("h6_p_pkey", name("23505", "duplicate key violates primary key constraint \"h6_p_pkey\" on (id)"));
        assertEquals("uk_h6_sku", name("23505", "duplicate key violates unique constraint \"uk_h6_sku\" on (sku)"));
        assertEquals("ux_h6_n", name("23505", "duplicate key violates unique index ux_h6_n"));
        assertEquals("ck_h6_n", name("23514", "new row for \"h6_p\" violates check constraint \"ck_h6_n\""));
        assertEquals("fk_h6", name("23503",
                "insert or update on \"h6_c\" violates foreign key \"fk_h6\": no matching row in \"h6_p\""));
    }

    @Test
    void otherErrorsNameNoConstraint() {
        assertNull(NusaConstraintNames.of(new SQLException("nusadb: NULL assigned to NOT NULL column `p`", "23502")));
        assertNull(name("42P01", "violates unique constraint \"x\""));
        SQLException chained = new SQLException("batch failed", "HY000");
        chained.setNextException(new SQLException("nusadb: constraint violation: violates foreign key \"fk_a\"", "23503"));
        assertEquals("fk_a", NusaConstraintNames.of(chained));
    }
}
