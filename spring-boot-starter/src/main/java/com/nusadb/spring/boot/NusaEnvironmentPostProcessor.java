package com.nusadb.spring.boot;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

/**
 * Fills in what Spring Boot cannot work out for a {@code jdbc:nusadb:} URL, so
 * {@code spring.datasource.url}, {@code username} and {@code password} are enough:
 *
 * <ul>
 *   <li>{@code spring.datasource.driver-class-name}: Spring Boot looks the driver up in a fixed
 *       list of databases, which NusaDB is not in.</li>
 *   <li>{@code spring.datasource.hikari.exception-override-class-name}: keeps HikariCP from
 *       closing a connection on SQLState {@code 0A000}, see {@link NusaHikariExceptionOverride}.</li>
 * </ul>
 *
 * <p>A property the application sets to a value is left alone; one that is unset or blank gets the
 * NusaDB default. Nothing changes for any other URL. Hibernate needs nothing here: it picks
 * {@code NusaDialect} from the connection itself.
 */
public class NusaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String URL_PREFIX = "jdbc:nusadb:";
    static final String DRIVER = "com.nusadb.jdbc.NusaDriver";
    static final String PROPERTY_SOURCE = "nusadb-defaults";

    static final String URL = "spring.datasource.url";
    static final String DRIVER_CLASS_NAME = "spring.datasource.driver-class-name";
    static final String HIKARI_EXCEPTION_OVERRIDE = "spring.datasource.hikari.exception-override-class-name";

    /** After application.properties and the other config data are loaded. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Binder binder = Binder.get(environment);
        if (!value(binder, URL).startsWith(URL_PREFIX)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (value(binder, DRIVER_CLASS_NAME).isBlank()) {
            defaults.put(DRIVER_CLASS_NAME, DRIVER);
        }
        if (ClassUtils.isPresent("com.zaxxer.hikari.SQLExceptionOverride", getClass().getClassLoader())
                && value(binder, HIKARI_EXCEPTION_OVERRIDE).isBlank()) {
            defaults.put(HIKARI_EXCEPTION_OVERRIDE, NusaHikariExceptionOverride.class.getName());
        }
        if (!defaults.isEmpty()) {
            // First, so a blank value given on the command line (--x=) counts as unset.
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, defaults));
        }
    }

    private static String value(Binder binder, String name) {
        return binder.bind(name, String.class).map(String::trim).orElse("");
    }
}
