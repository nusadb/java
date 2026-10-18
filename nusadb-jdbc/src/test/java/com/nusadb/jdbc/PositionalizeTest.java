package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@code ?} → {@code $n} rewriting, which runs client-side and needs no server. */
class PositionalizeTest {

    @Test
    void numbersMarkersInOrder() {
        assertEquals("WHERE a = $1 AND b = $2", NusaConnection.positionalize("WHERE a = ? AND b = ?"));
    }

    @Test
    void leavesMarkersInsideStringLiteralsAlone() {
        assertEquals("VALUES ($1, '? literal', $2)", NusaConnection.positionalize("VALUES (?, '? literal', ?)"));
    }

    @Test
    void leavesMarkersInsideQuotedIdentifiersAlone() {
        assertEquals("SELECT \"c?\" FROM t WHERE x = $1",
                NusaConnection.positionalize("SELECT \"c?\" FROM t WHERE x = ?"));
    }

    @Test
    void leavesDollarPlaceholdersUnchanged() {
        String already = "INSERT INTO t VALUES ($1, $2)";
        assertEquals(already, NusaConnection.positionalize(already));
    }
}
