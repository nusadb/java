package com.nusadb.jdbc;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server-side statements of one connection, kept by SQL text and declared parameter types, so
 * a later {@code PreparedStatement} with the same SQL needs no Parse round trip
 * ({@code preparedStatementCacheQueries}). Beyond its capacity the least recently used are closed
 * on the server. The server plans a statement anew at every execution, so a kept one stays right
 * across schema changes ({@code ALTER TABLE}, a table dropped and created again).
 */
final class StatementCache {
    private final Wire wire;
    private final int capacity;
    private final LinkedHashMap<String, String> names = new LinkedHashMap<>(16, 0.75f, true);

    StatementCache(Wire wire, int capacity) {
        this.wire = wire;
        this.capacity = capacity;
    }

    /** Whether statements are kept; with a capacity of 0 each PreparedStatement closes its own. */
    boolean keeps() {
        return capacity > 0;
    }

    /** The server statement for {@code sql} declaring {@code types}: kept from before, or prepared now. */
    String statement(String sql, byte[] types) throws SQLException {
        String key = Arrays.toString(types) + sql;
        String name = names.get(key);
        if (name != null) {
            return name;
        }
        name = wire.prepareStatement(sql, types);
        if (capacity > 0) {
            names.put(key, name);
            evict();
        }
        return name;
    }

    private void evict() {
        Iterator<Map.Entry<String, String>> oldest = names.entrySet().iterator();
        while (names.size() > capacity && oldest.hasNext()) {
            String name = oldest.next().getValue();
            oldest.remove();
            try {
                wire.closeStatement(name);
            } catch (SQLException ignored) {
                // the statement goes away with the session at the latest
            }
        }
    }

    int size() {
        return names.size();
    }
}
