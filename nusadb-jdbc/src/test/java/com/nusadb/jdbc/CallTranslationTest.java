package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nusadb.jdbc.NusaConnection.CallMode;
import org.junit.jupiter.api.Test;

class CallTranslationTest {

    private static NusaCallableStatement.Call t(String sql, CallMode mode) {
        return NusaCallableStatement.translate(sql, mode);
    }

    @Test
    void returnValueCalls() {
        NusaCallableStatement.Call call = t("{? = call f(?, ?)}", CallMode.SELECT);
        assertEquals("SELECT f(?, ?)", call.sql);
        assertTrue(call.hasReturn);
        assertEquals("SELECT s.f()", t(" { ?=CALL s.f } ", CallMode.CALL).sql);
        assertEquals("SELECT f('a,b', {fn ucase(?)})", t("{?= call f('a,b', {fn ucase(?)})}", CallMode.SELECT).sql,
                "nested escapes are left for the escape translation");
    }

    @Test
    void plainCallsFollowTheCallMode() {
        assertEquals("SELECT p(?) AS result", t("{call p(?)}", CallMode.SELECT).sql);
        assertEquals("CALL p(?)", t("{call p(?)}", CallMode.CALL_IF_NO_RETURN).sql);
        assertEquals("CALL p()", t("{call p}", CallMode.CALL).sql);
        assertFalse(t("{call p(?)}", CallMode.CALL).hasReturn);
    }

    @Test
    void otherSqlIsLeftAsWritten() {
        assertEquals("CALL p(?)", t("CALL p(?)", CallMode.SELECT).sql);
        assertEquals("SELECT f(1)", t("SELECT f(1)", CallMode.SELECT).sql);
    }
}
