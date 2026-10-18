package com.nusadb.jdbc;

/**
 * An asynchronous {@code LISTEN}/{@code NOTIFY} message delivered by the server. Obtained from
 * {@link NusaListener#getNotifications()} / {@link NusaListener#pollNotification(int)}.
 */
public final class NusaNotification {
    private final long pid;
    private final String channel;
    private final String payload;

    NusaNotification(long pid, String channel, String payload) {
        this.pid = pid;
        this.channel = channel;
        this.payload = payload;
    }

    /** Backend pid of the connection that issued the {@code NOTIFY}. */
    public long getPid() {
        return pid;
    }

    /** The channel the notification was sent on. */
    public String getChannel() {
        return channel;
    }

    /** The payload (empty string when {@code NOTIFY} carried none). */
    public String getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "NusaNotification{pid=" + pid + ", channel=" + channel + ", payload=" + payload + '}';
    }
}
