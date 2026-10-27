package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which queries an updatable result set can write back to, and the table it finds. */
class UpdatableQueryTest {

    private static RowUpdater.TableRef table(String sql) {
        return RowUpdater.table(sql, new String[1]);
    }

    private static String problem(String sql) {
        String[] error = new String[1];
        assertNull(RowUpdater.table(sql, error), sql);
        return error[0];
    }

    @Test
    void singleTableQueries() {
        assertEquals(new RowUpdater.TableRef(null, "t"), table("SELECT * FROM t"));
        assertEquals(new RowUpdater.TableRef(null, "t"), table("select a, b from T where a = $1 order by b"));
        assertEquals(new RowUpdater.TableRef("app", "Mixed"), table("SELECT x.a FROM app.\"Mixed\" AS x LIMIT 5"));
        assertEquals(new RowUpdater.TableRef(null, "t"), table("SELECT a FROM t x FOR UPDATE"));
        assertEquals(new RowUpdater.TableRef(null, "t"), table("SELECT a, (SELECT max(b) FROM u) FROM t;"));
        assertEquals(new RowUpdater.TableRef(null, "t"), table("/* c */ SELECT 'from u, v' AS s, a FROM t -- x"));
        assertEquals(new RowUpdater.TableRef(null, "t"), table("SELECT count(DISTINCT a) FROM t WHERE a IN (SELECT a FROM u)"));
    }

    @Test
    void otherQueriesAreRefused() {
        assertTrue(problem("SELECT * FROM t JOIN u ON t.a = u.a").contains("JOIN"));
        assertTrue(problem("SELECT * FROM t, u").contains("more than one table"));
        assertTrue(problem("SELECT * FROM (SELECT 1) s").contains("plain table"));
        assertTrue(problem("SELECT a FROM t GROUP BY a").contains("GROUP"));
        assertTrue(problem("SELECT a FROM t UNION SELECT a FROM u").contains("UNION"));
        assertTrue(problem("SELECT DISTINCT a FROM t").contains("DISTINCT"));
        assertTrue(problem("SELECT 1").contains("no table"));
        assertTrue(problem("WITH c AS (SELECT 1) SELECT * FROM c").contains("not a SELECT"));
        assertTrue(problem("SELECT * FROM generate_series(1, 3) g").contains("more than one table"));
    }
}
