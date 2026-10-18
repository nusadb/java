package com.nusadb.jdbc;

import java.sql.SQLException;
import java.util.List;

/**
 * The asynchronous {@code LISTEN}/{@code NOTIFY} API of a NusaDB connection. JDBC has no standard
 * notification surface, so reach it by unwrapping the connection:
 *
 * <pre>{@code
 * NusaListener listener = conn.unwrap(NusaListener.class);
 * listener.listen("orders");
 * NusaNotification note = listener.pollNotification(5000);
 * }</pre>
 */
public interface NusaListener {
    /** Subscribe to notifications on {@code channel} ({@code LISTEN channel}). */
    void listen(String channel) throws SQLException;

    /** Stop listening on {@code channel} ({@code UNLISTEN channel}); {@code null} unlistens all. */
    void unlisten(String channel) throws SQLException;

    /** Send a notification with an optional {@code payload} ({@code NOTIFY channel[, 'payload']}). */
    void notify(String channel, String payload) throws SQLException;

    /** Return and clear the notifications already received (no socket read). */
    List<NusaNotification> getNotifications() throws SQLException;

    /**
     * Wait up to {@code timeoutMillis} for the next notification ({@code null} on timeout; a value of
     * {@code 0} blocks until one arrives). Only meaningful after {@link #listen(String)}.
     */
    NusaNotification pollNotification(int timeoutMillis) throws SQLException;
}
