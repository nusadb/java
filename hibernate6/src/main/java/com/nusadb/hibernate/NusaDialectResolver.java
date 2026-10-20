package com.nusadb.hibernate;

import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver;

/**
 * Picks {@link NusaDialect} when the JDBC connection reports the product name {@code NusaDB}, so
 * {@code hibernate.dialect} can be left unset. Registered through
 * {@code META-INF/services/org.hibernate.engine.jdbc.dialect.spi.DialectResolver}.
 */
public class NusaDialectResolver implements DialectResolver {

    static final String PRODUCT_NAME = "NusaDB";

    @Override
    public Dialect resolveDialect(DialectResolutionInfo info) {
        return PRODUCT_NAME.equalsIgnoreCase(info.getDatabaseName()) ? new NusaDialect() : null;
    }
}
