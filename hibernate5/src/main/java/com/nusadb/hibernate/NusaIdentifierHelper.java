package com.nusadb.hibernate;

import java.util.Locale;
import java.util.Set;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelper;

/**
 * Quotes the table and column names the server does not take as plain identifiers, such as a
 * column named {@code key}, {@code index} or {@code interval}, so entities with such names work
 * without {@code @Column(name = "\"key\"")}. The quoted name is lower case, the same name the
 * server gives an unquoted one, so native SQL that quotes it or not still finds it. Everything
 * else is left to Hibernate's helper.
 */
final class NusaIdentifierHelper implements IdentifierHelper {

    /** Words the server rejects as a column or table name in the DDL and DML Hibernate writes. */
    static final Set<String> RESERVED = Set.of(
            "all", "check", "constraint", "current_catalog", "current_date", "current_schema",
            "current_time", "current_timestamp", "current_user", "distinct", "exists", "false",
            "foreign", "from", "index", "interval", "key", "lateral", "like", "local", "localtime",
            "localtimestamp", "not", "null", "only", "primary", "replace", "select", "session_user",
            "table", "top", "trim", "true", "unique", "user");

    private final IdentifierHelper delegate;

    NusaIdentifierHelper(IdentifierHelper delegate) {
        this.delegate = delegate;
    }

    @Override
    public Identifier normalizeQuoting(Identifier identifier) {
        Identifier normalized = delegate.normalizeQuoting(identifier);
        if (normalized != null && !normalized.isQuoted()
                && RESERVED.contains(normalized.getText().toLowerCase(Locale.ROOT))) {
            return Identifier.toIdentifier(normalized.getText().toLowerCase(Locale.ROOT), true);
        }
        return normalized;
    }

    @Override
    public Identifier toIdentifier(String text) {
        return normalizeQuoting(Identifier.toIdentifier(text));
    }

    @Override
    public Identifier toIdentifier(String text, boolean quoted) {
        return normalizeQuoting(Identifier.toIdentifier(text, quoted));
    }

    @Override
    public Identifier applyGlobalQuoting(String text) {
        return delegate.applyGlobalQuoting(text);
    }

    @Override
    public boolean isReservedWord(String word) {
        return delegate.isReservedWord(word) || word != null && RESERVED.contains(word.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toMetaDataCatalogName(Identifier catalogIdentifier) {
        return delegate.toMetaDataCatalogName(catalogIdentifier);
    }

    @Override
    public String toMetaDataSchemaName(Identifier schemaIdentifier) {
        return delegate.toMetaDataSchemaName(schemaIdentifier);
    }

    @Override
    public String toMetaDataObjectName(Identifier identifier) {
        return delegate.toMetaDataObjectName(identifier);
    }
}
