package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Driver discovery and URL scheme handling: pure classpath concerns, no server needed. */
class DriverRegistrationTest {

    private static final String URL = "jdbc:nusadb://localhost:5678/nusadb";

    @Test
    void driverManagerFindsTheDriverThroughServiceLoader() throws Exception {
        // No Class.forName: META-INF/services/java.sql.Driver must be enough.
        Driver d = DriverManager.getDriver(URL);
        assertInstanceOf(NusaDriver.class, d);
    }

    @Test
    void acceptsOnlyTheNusadbScheme() {
        NusaDriver d = new NusaDriver();
        assertTrue(d.acceptsURL(URL));
        assertTrue(d.acceptsURL("jdbc:nusadb://user:pw@db.example.com/app"));
        assertFalse(d.acceptsURL("jdbc:otherdb://localhost:5432/db"));
        assertFalse(d.acceptsURL(null));
    }

    @Test
    void connectReturnsNullForAForeignUrl() throws Exception {
        // JDBC contract: a driver that does not handle the URL returns null so DriverManager tries the next one.
        assertNull(new NusaDriver().connect("jdbc:otherdb://localhost:5432/db", new Properties()));
    }
}
