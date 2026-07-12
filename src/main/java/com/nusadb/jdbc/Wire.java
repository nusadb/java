package com.nusadb.jdbc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** The low-level protocol client: socket, handshake/auth, simple + extended query. */
final class Wire {
    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    long backendPid;
    long backendSecret;
    boolean hasKey;
    /** The database this connection opened (the JDBC "catalog"), set at {@link #connect}. */
    String database;
    private int counter;
    /** Async LISTEN/NOTIFY messages received while reading other responses; drained by the connection. */
    private final Deque<NusaNotification> notifications = new ArrayDeque<>();

    private Wire(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    static Wire connect(String host, int port, String user, String database, String password)
            throws SQLException {
        try {
            Socket socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            Wire wire = new Wire(socket);
            wire.database = database;
            wire.handshake(user, database, password);
            return wire;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("nusadb: connect failed: " + e.getMessage(), "08001", e);
        }
    }

    /** A collected result: column names, per-column type names, rows, and the command tag. */
    static final class Result {
        final List<String> columns;
        /** Per-column type name (protocol 1.1), parallel to {@link #columns}; an entry is {@code null}
         * when the server answered with the untyped (1.0) row description. */
        final List<String> columnTypes;
        final List<List<byte[]>> rows;
        final String tag;

        Result(List<String> columns, List<String> columnTypes, List<List<byte[]>> rows, String tag) {
            this.columns = columns;
            this.columnTypes = columnTypes;
            this.rows = rows;
            this.tag = tag;
        }
    }

    private void send(byte[] frame) throws IOException {
        Protocol.write(out, frame);
    }

    private void handshake(String user, String database, String password) throws Exception {
        send(Protocol.startup(user, database));
        while (true) {
            Protocol.Message msg = Protocol.readMessage(in);
            if (msg.type == Protocol.B_READY) {
                return;
            } else if (msg.type == Protocol.B_ERROR) {
                throw serverError(msg);
            } else if (msg.type == Protocol.B_AUTH) {
                Protocol.Reader r = msg.reader();
                long sub = r.u32();
                if (sub == Protocol.AUTH_SASL) {
                    scram(r, user, password);
                }
            } else if (msg.type == Protocol.B_BACKEND_KEY) {
                Protocol.Reader r = msg.reader();
                backendPid = r.u32();
                backendSecret = r.u32();
                hasKey = true;
            }
        }
    }

    private void scram(Protocol.Reader offer, String user, String password) throws Exception {
        int count = offer.u16();
        boolean supported = false;
        for (int i = 0; i < count; i++) {
            if (Protocol.SCRAM_MECHANISM.equals(offer.str())) {
                supported = true;
            }
        }
        if (!supported) {
            throw new SQLException("nusadb: server offered no supported SASL mechanism", "28000");
        }
        if (password == null) {
            throw new SQLException("nusadb: server requires a password but none was given", "28000");
        }

        Scram scram = Scram.start(user);
        send(Protocol.saslInitial(Protocol.SCRAM_MECHANISM,
                scram.fullClientFirst.getBytes(StandardCharsets.UTF_8)));

        Protocol.Message msg = Protocol.readMessage(in);
        if (msg.type == Protocol.B_ERROR) {
            throw serverError(msg);
        }
        Protocol.Reader r = msg.reader();
        if (msg.type != Protocol.B_AUTH || r.u32() != Protocol.AUTH_SASL_CONTINUE) {
            throw new SQLException("nusadb: expected a SASL continue message", "08P01");
        }
        String serverFirst = new String(r.rest(), StandardCharsets.UTF_8);

        send(Protocol.saslResponse(scram.clientFinal(password, serverFirst)));

        msg = Protocol.readMessage(in);
        if (msg.type == Protocol.B_ERROR) {
            throw serverError(msg);
        }
        r = msg.reader();
        if (msg.type != Protocol.B_AUTH || r.u32() != Protocol.AUTH_SASL_FINAL) {
            throw new SQLException("nusadb: expected a SASL final message", "08P01");
        }
        if (!scram.verifyServerFinal(new String(r.rest(), StandardCharsets.UTF_8))) {
            throw new SQLException("nusadb: server signature did not verify", "28000");
        }
    }

    private Result collect() throws SQLException {
        List<String> columns = new ArrayList<>();
        List<String> columnTypes = new ArrayList<>();
        List<List<byte[]>> rows = new ArrayList<>();
        String tag = null;
        SQLException error = null;
        try {
            while (true) {
                Protocol.Message msg = Protocol.readMessage(in);
                Protocol.Reader r = msg.reader();
                switch (msg.type) {
                    case Protocol.B_ROW_DESCRIPTION: {
                        int n = r.u16();
                        columns = new ArrayList<>(n);
                        columnTypes = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            columns.add(r.str());
                            columnTypes.add(null);
                        }
                        break;
                    }
                    case Protocol.B_ROW_DESCRIPTION_TYPED: {
                        // Protocol 1.1: each column is a name plus a 1-byte type tag (§9.2).
                        int n = r.u16();
                        columns = new ArrayList<>(n);
                        columnTypes = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            columns.add(r.str());
                            columnTypes.add(Protocol.typeName(r.u8()));
                        }
                        break;
                    }
                    case Protocol.B_DATA_ROW:
                        rows.add(r.fields());
                        break;
                    case Protocol.B_COMMAND_COMPLETE:
                        tag = r.str();
                        break;
                    case Protocol.B_NOTIFICATION:
                        // A pending LISTEN/NOTIFY message can lead the next query's response; buffer it.
                        notifications.addLast(decodeNotification(r));
                        break;
                    case Protocol.B_ERROR:
                        error = serverError(msg);
                        break;
                    case Protocol.B_READY:
                        if (error != null) {
                            throw error;
                        }
                        return new Result(columns, columnTypes, rows, tag);
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            throw new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
        }
    }

    synchronized Result simpleQuery(String sql) throws SQLException {
        try {
            send(Protocol.query(sql));
        } catch (IOException e) {
            throw new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
        }
        return collect();
    }

    private static NusaNotification decodeNotification(Protocol.Reader r) {
        long pid = r.u32();
        String channel = r.str();
        String payload = r.str();
        return new NusaNotification(pid, channel, payload);
    }

    /** Return and clear the notifications buffered while reading other responses. */
    synchronized List<NusaNotification> drainNotifications() {
        List<NusaNotification> out = new ArrayList<>(notifications);
        notifications.clear();
        return out;
    }

    /**
     * Wait up to {@code timeoutMillis} for the next notification ({@code null} on timeout; {@code 0}
     * blocks). Returns a buffered notification immediately if one is pending.
     */
    synchronized NusaNotification pollNotification(int timeoutMillis) throws SQLException {
        if (!notifications.isEmpty()) {
            return notifications.pollFirst();
        }
        try {
            socket.setSoTimeout(timeoutMillis);
            Protocol.Message msg = Protocol.readMessage(in);
            if (msg.type == Protocol.B_NOTIFICATION) {
                return decodeNotification(msg.reader());
            }
            if (msg.type == Protocol.B_ERROR) {
                throw serverError(msg);
            }
            throw new SQLException(
                    "nusadb: unexpected message '" + (char) msg.type + "' while polling for notifications",
                    "08006");
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            throw new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
        } finally {
            try {
                socket.setSoTimeout(0);
            } catch (IOException ignored) {
                // best effort: restore blocking reads
            }
        }
    }

    synchronized Result extendedQuery(String sql, List<byte[]> params) throws SQLException {
        try {
            send(Protocol.parse("", sql));
            send(Protocol.bind("", "", params));
            send(Protocol.describePortal(""));
            send(Protocol.execute("", 0));
            send(Protocol.sync());
        } catch (IOException e) {
            throw new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
        }
        return collect();
    }

    synchronized String prepareStatement(String sql) throws SQLException {
        String name = "nusa_stmt_" + (++counter);
        try {
            send(Protocol.parse(name, sql));
            send(Protocol.sync());
        } catch (IOException e) {
            throw new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
        }
        collect(); // surfaces a parse error
        return name;
    }

    synchronized Result executePrepared(String statement, List<byte[]> params) throws SQLException {
        String portal = "nusa_portal_" + (++counter);
        try {
            send(Protocol.bind(portal, statement, params));
            send(Protocol.describePortal(portal));
            send(Protocol.execute(portal, 0));
            send(Protocol.sync());
        } catch (IOException e) {
            throw new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
        }
        return collect();
    }

    /**
     * Bulk-load via {@code COPY ... FROM STDIN} (§12.1): send the COPY statement, stream {@code source}
     * (already in the server's text format — tab-delimited, {@code \N} for NULL) in 64 KiB
     * {@code CopyData} chunks, finish with {@code CopyDone}, and return the loaded row count. A read
     * error on {@code source} sends {@code CopyFail} and leaves the connection ready; a refused COPY
     * surfaces as the server's error.
     */
    synchronized long copyIn(String sql, java.io.InputStream source) throws SQLException {
        try {
            send(Protocol.query(sql));
            awaitCopyStart(Protocol.B_COPY_IN_RESPONSE);
            byte[] buf = new byte[64 * 1024];
            try {
                int n;
                while ((n = source.read(buf)) != -1) {
                    if (n > 0) {
                        send(Protocol.copyData(n == buf.length ? buf : java.util.Arrays.copyOf(buf, n)));
                    }
                }
            } catch (IOException e) {
                send(Protocol.copyFail("nusadb: client read error during COPY"));
                drainToReady();
                throw new SQLException("nusadb: COPY source read failed: " + e.getMessage(), "57014", e);
            }
            send(Protocol.copyDone());
            return finishCopy();
        } catch (IOException e) {
            throw new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
        }
    }

    /**
     * Bulk-export via {@code COPY ... TO STDOUT} (§12.2): send the COPY statement, write every
     * {@code CopyData} chunk into {@code sink}, and return the exported row count.
     */
    synchronized long copyOut(String sql, OutputStream sink) throws SQLException {
        try {
            send(Protocol.query(sql));
            awaitCopyStart(Protocol.B_COPY_OUT_RESPONSE);
            while (true) {
                Protocol.Message msg = Protocol.readMessage(in);
                if (msg.type == Protocol.B_COPY_DATA) {
                    try {
                        sink.write(msg.payload);
                    } catch (IOException e) {
                        drainToReady();
                        throw new SQLException(
                                "nusadb: COPY sink write failed: " + e.getMessage(), "57014", e);
                    }
                } else if (msg.type == Protocol.B_COPY_DONE) {
                    break;
                } else if (msg.type == Protocol.B_ERROR) {
                    SQLException err = serverError(msg);
                    drainToReady();
                    throw err;
                }
            }
            return finishCopy();
        } catch (IOException e) {
            throw new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
        }
    }

    /** Read until the expected {@code CopyInResponse}/{@code CopyOutResponse}; a refused COPY arrives
     * as {@code Error} then {@code ReadyForQuery} and is surfaced, connection left ready. */
    private void awaitCopyStart(byte expected) throws SQLException, IOException {
        SQLException err = null;
        while (true) {
            Protocol.Message msg = Protocol.readMessage(in);
            if (msg.type == expected) {
                return;
            } else if (msg.type == Protocol.B_ERROR) {
                err = serverError(msg);
            } else if (msg.type == Protocol.B_READY) {
                throw err != null ? err : new SQLException("nusadb: COPY did not start", "08P01");
            }
        }
    }

    /** Drain to {@code ReadyForQuery}, returning the row count from the {@code COPY <n>} tag. */
    private long finishCopy() throws SQLException, IOException {
        String tag = null;
        SQLException err = null;
        while (true) {
            Protocol.Message msg = Protocol.readMessage(in);
            if (msg.type == Protocol.B_COMMAND_COMPLETE) {
                tag = msg.reader().str();
            } else if (msg.type == Protocol.B_ERROR) {
                err = serverError(msg);
            } else if (msg.type == Protocol.B_READY) {
                if (err != null) {
                    throw err;
                }
                return copyCount(tag);
            }
        }
    }

    /** Best-effort resync: read and discard frames until {@code ReadyForQuery}. */
    private void drainToReady() {
        try {
            while (Protocol.readMessage(in).type != Protocol.B_READY) {
                // discard
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static long copyCount(String tag) {
        if (tag == null) {
            return 0;
        }
        int sp = tag.lastIndexOf(' ');
        if (sp < 0) {
            return 0;
        }
        try {
            return Long.parseLong(tag.substring(sp + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    void close() {
        try {
            send(Protocol.terminate());
        } catch (IOException ignored) {
            // best effort
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static SQLException serverError(Protocol.Message msg) {
        Protocol.Reader r = msg.reader();
        String code = r.str();
        String message = r.str();
        return new SQLException("nusadb: " + message, code);
    }
}
