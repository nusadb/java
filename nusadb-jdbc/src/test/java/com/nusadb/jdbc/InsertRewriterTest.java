package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Which INSERTs the batch rewrite touches, and what it produces; no server needed. */
class InsertRewriterTest {

    @Test
    void rewritesAPlainSingleRowInsert() {
        InsertRewriter r = InsertRewriter.of("INSERT INTO t (a, b) VALUES ($1, $2)");
        assertNotNull(r);
        assertEquals(2, r.paramsPerRow());
        assertEquals("INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4), ($5, $6)", r.sql(3));
    }

    @Test
    void keepsWhatFollowsTheTupleAndDropsATrailingSemicolon() {
        InsertRewriter r = InsertRewriter.of("insert into t values ($1, 'a,b', $2) on conflict do nothing;");
        assertNotNull(r);
        assertEquals("insert into t values ($1, 'a,b', $2), ($3, 'a,b', $4) on conflict do nothing", r.sql(2));
    }

    @Test
    void placeholderTextInsideALiteralIsNotRenumbered() {
        InsertRewriter r = InsertRewriter.of("INSERT INTO t VALUES ($1, '$1 stays')");
        assertNotNull(r);
        assertEquals("INSERT INTO t VALUES ($1, '$1 stays'), ($2, '$1 stays')", r.sql(2));
    }

    @Test
    void expressionsInsideTheTupleAreFine() {
        InsertRewriter r = InsertRewriter.of("INSERT INTO t VALUES (lower($1), coalesce($2, 0))");
        assertNotNull(r);
        assertEquals("INSERT INTO t VALUES (lower($1), coalesce($2, 0)), (lower($3), coalesce($4, 0))", r.sql(2));
    }

    @Test
    void leavesOtherShapesAlone() {
        assertNull(InsertRewriter.of("UPDATE t SET a = $1"));
        assertNull(InsertRewriter.of("INSERT INTO t SELECT * FROM s WHERE id = $1"));
        assertNull(InsertRewriter.of("INSERT INTO t DEFAULT VALUES"));
        assertNull(InsertRewriter.of("INSERT INTO t VALUES ($1), ($2)"));
        assertNull(InsertRewriter.of("INSERT INTO t VALUES ($1) RETURNING id"));
        assertNull(InsertRewriter.of("INSERT INTO t VALUES ($1) ON CONFLICT (a) DO UPDATE SET b = $2"));
        assertNull(InsertRewriter.of("INSERT INTO t VALUES ($1, $3)")); // a gap: $2 unused
        assertNull(InsertRewriter.of("INSERT INTO t VALUES (1, 2)"));   // nothing to batch
    }

    @Test
    void rowsPerStatementStaysUnderTheParameterLimit() {
        InsertRewriter r = InsertRewriter.of("INSERT INTO t VALUES ($1, $2, $3)");
        assertNotNull(r);
        assertEquals(InsertRewriter.MAX_PARAMS / 3, r.rowsPerStatement());
    }

    @Test
    void versionParsing() {
        assertEquals(0, Version.parse("0.2.0-SNAPSHOT")[0]);
        assertEquals(2, Version.parse("0.2.0-SNAPSHOT")[1]);
        assertEquals(1, Version.parse("1.14")[0]);
        assertEquals(14, Version.parse("1.14")[1]);
        assertEquals(0, Version.parse("garbage")[0]);
    }
}
