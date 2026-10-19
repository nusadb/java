package com.nusadb.hibernate;

import java.util.Locale;
import org.hibernate.dialect.sequence.SequenceSupport;

/**
 * Native NusaDB sequences: {@code CREATE SEQUENCE ... START WITH .. INCREMENT BY ..} and
 * {@code nextval('name')}. An increment above 1 is honoured, so Hibernate's pooled optimizer
 * ({@code allocationSize > 1}) fetches one value per block of ids.
 *
 * <p>The server evaluates {@code nextval()} once per statement (a SELECT without FROM or a VALUES
 * row), so a block of values is never fetched with a per-row query. It also looks the string up
 * as the stored name, without folding case or reading a schema prefix, so {@link #storedName} does
 * that here: Hibernate 6 names an AUTO sequence {@code <table>_SEQ}, which an unquoted
 * {@code CREATE SEQUENCE} stores in lower case.
 */
final class NusaSequenceSupport implements SequenceSupport {

    static final NusaSequenceSupport INSTANCE = new NusaSequenceSupport();

    private NusaSequenceSupport() {}

    @Override
    public String getSelectSequenceNextValString(String sequenceName) {
        return "nextval('" + storedName(sequenceName) + "')";
    }

    @Override
    public String getSelectSequencePreviousValString(String sequenceName) {
        return "currval('" + storedName(sequenceName) + "')";
    }

    @Override
    public String getSequenceNextValString(String sequenceName) {
        return "select " + getSelectSequenceNextValString(sequenceName);
    }

    @Override
    public String getSequenceNextValString(String sequenceName, int increment) {
        return getSequenceNextValString(sequenceName);
    }

    @Override
    public String getFromDual() {
        return "";
    }

    @Override
    public String getDropSequenceString(String sequenceName) {
        return "drop sequence if exists " + sequenceName;
    }

    @Override
    public boolean sometimesNeedsStartingValue() {
        return true;
    }

    /**
     * The name a {@code CREATE SEQUENCE <sequenceName>} stores: the last part of a qualified name,
     * unquoted as written when quoted, lower case otherwise.
     */
    static String storedName(String sequenceName) {
        String name = sequenceName.trim();
        String stored;
        if (name.endsWith("\"")) {
            int open = name.lastIndexOf('"', name.length() - 2);
            stored = name.substring(open + 1, name.length() - 1).replace("\"\"", "\"");
        } else {
            stored = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        return stored.replace("'", "''");
    }
}
