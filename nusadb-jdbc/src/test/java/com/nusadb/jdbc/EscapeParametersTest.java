package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ESCAPE parameters written in as literals, the other parameters numbered down. */
class EscapeParametersTest {

    private static List<byte[]> values(String... v) {
        return Arrays.stream(v).map(s -> s == null ? null : s.getBytes(StandardCharsets.UTF_8)).toList();
    }

    @Test
    void escapeParameterBecomesALiteral() {
        EscapeParameters e = EscapeParameters.of("select * from t where upper(n) like upper($1) escape $2 and id > $3");
        List<byte[]> v = values("%A%", "\\", "7");
        assertEquals("select * from t where upper(n) like upper($1) escape '\\' and id > $2", e.sql(v));
        assertEquals(2, e.values(v).size());
        assertEquals("7", new String(e.values(v).get(1), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[] {1, 3}, e.tags(new byte[] {1, 2, 3}));
    }

    @Test
    void quotesNullAndRepeatedUse() {
        EscapeParameters e = EscapeParameters.of("select $1 where a LIKE $1 ESCAPE $2 or b like $3 escape $2");
        assertEquals("select $1 where a LIKE $1 ESCAPE '''' or b like $2 escape ''''", e.sql(values("x", "'", "y")));
        assertEquals("select $1 where a LIKE $1 ESCAPE NULL or b like $2 escape NULL", e.sql(values("x", null, "y")));
        // a parameter used elsewhere too is kept
        EscapeParameters kept = EscapeParameters.of("select $1 where a like 'x' escape $1");
        assertEquals("select $1 where a like 'x' escape '!'", kept.sql(values("!")));
        assertEquals(1, kept.values(values("!")).size());
    }

    @Test
    void textInLiteralsAndCommentsIsLeftAlone() {
        assertNull(EscapeParameters.of("select 'escape $1', \"escape $2\" -- escape $3\n /* escape $4 */ from t where a = $1"));
        assertNull(EscapeParameters.of("select * from t where a like $1"));
        assertNull(EscapeParameters.of("select * from t where unescape $1"));
    }
}
