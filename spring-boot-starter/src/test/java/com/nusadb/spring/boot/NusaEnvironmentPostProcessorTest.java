package com.nusadb.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class NusaEnvironmentPostProcessorTest {

    private static StandardEnvironment process(String... keyValues) {
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.put(keyValues[i], keyValues[i + 1]);
        }
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("app", props));
        new NusaEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        return env;
    }

    @Test
    void aNusaUrlGetsTheDriverAndTheHikariOverride() {
        StandardEnvironment env = process("spring.datasource.url", "jdbc:nusadb://db:5678/app");
        assertEquals("com.nusadb.jdbc.NusaDriver", env.getProperty("spring.datasource.driver-class-name"));
        assertEquals(NusaHikariExceptionOverride.class.getName(),
                env.getProperty("spring.datasource.hikari.exception-override-class-name"));
    }

    @Test
    void aBlankValueCountsAsUnset() {
        StandardEnvironment env = process("spring.datasource.url", "jdbc:nusadb://db:5678/app",
                "spring.datasource.driver-class-name", " ");
        assertEquals("com.nusadb.jdbc.NusaDriver", env.getProperty("spring.datasource.driver-class-name"));
    }

    @Test
    void valuesTheApplicationSetAreKept() {
        StandardEnvironment env = process("spring.datasource.url", "jdbc:nusadb://db:5678/app",
                "spring.datasource.driverClassName", "com.example.WrappingDriver",
                "spring.datasource.hikari.exception-override-class-name", "com.example.Override");
        assertEquals("com.example.WrappingDriver", env.getProperty("spring.datasource.driverClassName"));
        assertNull(env.getProperty("spring.datasource.driver-class-name"));
        assertEquals("com.example.Override", env.getProperty("spring.datasource.hikari.exception-override-class-name"));
        assertFalse(env.getPropertySources().contains(NusaEnvironmentPostProcessor.PROPERTY_SOURCE));
    }

    @Test
    void otherDatabasesAreLeftAlone() {
        StandardEnvironment env = process("spring.datasource.url", "jdbc:otherdb://db:1234/app");
        assertNull(env.getProperty("spring.datasource.driver-class-name"));
        assertFalse(env.getPropertySources().contains(NusaEnvironmentPostProcessor.PROPERTY_SOURCE));
        assertFalse(process().getPropertySources().contains(NusaEnvironmentPostProcessor.PROPERTY_SOURCE));
    }
}
