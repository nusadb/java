package com.nusadb.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.SQLExceptionOverride.Override;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class NusaHikariExceptionOverrideTest {

    private static Override adjudicate(String state) {
        return new NusaHikariExceptionOverride().adjudicate(new SQLException("x", state));
    }

    @Test
    void onlyFeatureNotSupportedKeepsTheConnection() {
        assertEquals(Override.DO_NOT_EVICT, adjudicate("0A000"));
        assertEquals(Override.CONTINUE_EVICT, adjudicate("08006"));
        assertEquals(Override.CONTINUE_EVICT, adjudicate("57P01"));
        assertEquals(Override.CONTINUE_EVICT, adjudicate("23505"));
        assertEquals(Override.CONTINUE_EVICT, adjudicate(null));
    }
}
