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
    /** How this connection was opened; reused to open the side connection a cancel needs. */
    private Options options;
    /** Socket read timeout in milliseconds while waiting for a reply (0 = wait forever). */
    private volatile int readTimeoutMillis;
    /** Set once the stream can no longer be trusted (I/O error, read timeout, forced close). */
    private volatile boolean broken;
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

    /** Everything needed to open a socket to the server (again, for a cancel request). */
    static final class Options {
        String host = "127.0.0.1";
        int port = 5678;
        String user;
        String database = "nusadb";
        String password;
        boolean tls;
        String tlsCa;
        boolean tlsInsecure;
        String tlsDomain;
        /** TCP connect + TLS handshake + login budget in milliseconds (0 = no limit). */
        int connectTimeoutMillis;
        /** Default read timeout for replies in milliseconds (0 = wait forever). */
        int socketTimeoutMillis;
        /** Turn on TCP keep-alive probes so a dead peer is noticed on an idle connection. */
        boolean tcpKeepAlive;
    }

    static Wire connect(String host, int port, String user, String database, String password)
            throws SQLException {
        Options o = new Options();
        o.host = host;
        o.port = port;
        o.user = user;
        o.database = database;
        o.password = password;
        return connect(o);
    }

    static Wire connect(Options o) throws SQLException {
        Socket socket = null;
        try {
            socket = openSocket(o);
            // The handshake (and SCRAM) is bounded by the connect timeout too, so a server that
            // accepts the TCP connection but never answers cannot hang the caller.
            socket.setSoTimeout(o.connectTimeoutMillis);
            Wire wire = new Wire(socket);
            wire.options = o;
            wire.database = o.database;
            wire.handshake(o.user, o.database, o.password);
            wire.readTimeoutMillis = o.socketTimeoutMillis;
            socket.setSoTimeout(o.socketTimeoutMillis);
            return wire;
        } catch (SQLException e) {
            closeQuietly(socket);
            throw e;
        } catch (SocketTimeoutException e) {
            closeQuietly(socket);
            throw new java.sql.SQLTimeoutException("nusadb: connect to " + o.host + ":" + o.port
                    + " timed out after " + o.connectTimeoutMillis + " ms", "08001", e);
        } catch (Exception e) {
            closeQuietly(socket);
            throw new SQLException("nusadb: connect failed: " + e.getMessage(), "08001", e);
        }
    }

    /** A connected socket (TLS-wrapped when configured), not yet carrying any wire frame. */
    private static Socket openSocket(Options o) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new java.net.InetSocketAddress(o.host, o.port), o.connectTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(o.tcpKeepAlive);
            if (o.tls) {
                // Implicit TLS 1.2+: the server (started with --tls-cert) expects the handshake before
                // any wire frame. Trust the server via `tlsCa` (a PEM CA/self-signed cert), or the JDK
                // trust store when absent; `tlsInsecure` skips verification (dev/test only).
                String peer = o.tlsDomain != null ? o.tlsDomain : o.host;
                javax.net.ssl.SSLContext ctx = buildSslContext(o.tlsCa, o.tlsInsecure);
                javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) ctx.getSocketFactory()
                        .createSocket(socket, peer, o.port, true);
                ssl.setUseClientMode(true);
                javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
                if (!o.tlsInsecure) {
                    p.setEndpointIdentificationAlgorithm("HTTPS");
                }
                p.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(peer)));
                ssl.setSSLParameters(p);
                ssl.setSoTimeout(o.connectTimeoutMillis);
                ssl.startHandshake();
                return ssl;
            }
            return socket;
        } catch (Exception e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort close
            }
        }
    }

    // Build an SSL context that trusts the given PEM CA (or the JDK default trust store when null),
    // or accepts any certificate when insecure (dev/test only).
    private static javax.net.ssl.SSLContext buildSslContext(String caPath, boolean insecure)
            throws Exception {
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        if (insecure) {
            javax.net.ssl.TrustManager[] all = {
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}

                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}

                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            };
            ctx.init(null, all, new java.security.SecureRandom());
            return ctx;
        }
        if (caPath == null) {
            ctx.init(null, null, null); // JDK default trust store
            return ctx;
        }
        java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        try (java.io.InputStream is = new java.io.FileInputStream(caPath)) {
            int i = 0;
            for (java.security.cert.Certificate cert : cf.generateCertificates(is)) {
                ks.setCertificateEntry("ca" + (i++), cert);
            }
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
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
            throw ioError(e);
        }
    }

    /**
     * An I/O failure (including a read timeout) in the middle of an exchange leaves unread frames
     * on the stream, so the connection is closed rather than reused out of step.
     */
    private SQLException ioError(IOException e) {
        broken = true;
        closeQuietly(socket);
        if (e instanceof SocketTimeoutException) {
            return new java.sql.SQLTimeoutException("nusadb: no reply from the server within "
                    + readTimeoutMillis + " ms; the connection is closed", "08006", e);
        }
        return new SQLException("nusadb: I/O error: " + e.getMessage(), "08006", e);
    }

    private void ensureUsable() throws SQLException {
        if (broken) {
            throw new SQLException("nusadb: connection is closed after an earlier I/O error", "08003");
        }
        settle();
    }

    boolean isBroken() {
        return broken;
    }

    synchronized Result simpleQuery(String sql) throws SQLException {
        ensureUsable();
        try {
            send(Protocol.query(sql));
        } catch (IOException e) {
            throw ioError(e);
        }
        return collect();
    }

    /**
     * {@code Connection.isValid}: a {@code SELECT 1} round trip that must answer within
     * {@code timeoutMillis} (0 = the connection's normal read timeout).
     */
    synchronized boolean ping(int timeoutMillis) {
        if (broken) {
            return false;
        }
        try {
            settle();
            if (timeoutMillis > 0) {
                socket.setSoTimeout(timeoutMillis);
            }
            send(Protocol.query("SELECT 1"));
            collect();
            return true;
        } catch (IOException e) {
            ioError(e);
            return false;
        } catch (SQLException e) {
            return !broken;
        } finally {
            restoreReadTimeout();
        }
    }

    /** {@code Connection.setNetworkTimeout}: how long to wait for any reply before giving up. */
    void setReadTimeout(int millis) throws SQLException {
        readTimeoutMillis = millis;
        try {
            socket.setSoTimeout(millis);
        } catch (IOException e) {
            throw ioError(e);
        }
    }

    int readTimeout() {
        return readTimeoutMillis;
    }

    private void restoreReadTimeout() {
        try {
            socket.setSoTimeout(readTimeoutMillis);
        } catch (IOException ignored) {
            // the socket is gone; the next exchange reports it
        }
    }

    /**
     * Ask the server to cancel whatever statement this connection is running (§13): open a fresh
     * connection, send {@code CancelRequest} with this connection's key, and hang up. Not
     * synchronized: it is called from another thread while the statement holds the lock. A
     * request that arrives when nothing is running cancels nothing.
     */
    void cancel() throws SQLException {
        if (!hasKey || options == null) {
            throw new java.sql.SQLFeatureNotSupportedException("nusadb: the server sent no cancel key");
        }
        Options o = options;
        try (Socket side = openSocket(o)) {
            side.setSoTimeout(o.connectTimeoutMillis > 0 ? o.connectTimeoutMillis : 10_000);
            Protocol.write(new BufferedOutputStream(side.getOutputStream()), Protocol.cancel(backendPid, backendSecret));
            // The server closes the side connection without a reply; wait for that so the request
            // is known to have been read before the caller moves on.
            side.getInputStream().read();
        } catch (SocketTimeoutException e) {
            // The request was written; the server simply has not hung up yet.
        } catch (Exception e) {
            throw new SQLException("nusadb: cancel request failed: " + e.getMessage(), "08006", e);
        }
    }

    /** {@code Connection.abort}: drop the socket at once, without the polite Terminate. */
    void forceClose() {
        broken = true;
        closeQuietly(socket);
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
        ensureUsable();
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
            // Waiting for a notification that has not come is not a failure of the stream.
            return null;
        } catch (IOException e) {
            throw ioError(e);
        } finally {
            restoreReadTimeout();
        }
    }

    synchronized Result extendedQuery(String sql, List<byte[]> params) throws SQLException {
        ensureUsable();
        try {
            send(Protocol.parse("", sql));
            send(Protocol.bind("", "", params));
            send(Protocol.describePortal(""));
            send(Protocol.execute("", 0));
            send(Protocol.sync());
        } catch (IOException e) {
            throw ioError(e);
        }
        return collect();
    }

    synchronized String prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, new byte[0]);
    }

    /** Prepares {@code sql} with the given parameter type tags (empty: all inferred). */
    synchronized String prepareStatement(String sql, byte[] paramTypes) throws SQLException {
        ensureUsable();
        String name = "nusa_stmt_" + (++counter);
        try {
            send(Protocol.parse(name, sql, paramTypes));
            send(Protocol.sync());
        } catch (IOException e) {
            throw ioError(e);
        }
        collect(); // surfaces a parse error
        return name;
    }

    synchronized Result executePrepared(String statement, List<byte[]> params) throws SQLException {
        ensureUsable();
        // The unnamed portal: the next Bind replaces it, so executing a statement many times on a
        // long-lived (pooled) connection leaves nothing behind on the server.
        try {
            send(Protocol.bind("", statement, params));
            send(Protocol.describePortal(""));
            send(Protocol.execute("", 0));
            send(Protocol.sync());
        } catch (IOException e) {
            throw ioError(e);
        }
        return collect();
    }

    /**
     * Rows of a batch sent per {@code Sync}. The Bind/Execute pairs of a chunk go out without
     * waiting, so the server's replies (a few dozen bytes per row) queue up while the client is
     * still writing; a bounded chunk keeps them well inside the socket buffers.
     */
    static final int BATCH_CHUNK = 256;

    /**
     * Runs a batch pipelined: for each row a Bind of {@code statements[i]} with {@code rows[i]} and
     * an Execute, then one Sync per {@link #BATCH_CHUNK} rows, then the replies are read. Returns
     * one update count per row. On an error the server skips the rest of the chunk; a
     * {@link java.sql.BatchUpdateException} then carries the counts of the rows before it.
     */
    synchronized long[] executeBatch(List<String> statements, List<List<byte[]>> rows) throws SQLException {
        ensureUsable();
        int total = rows.size();
        long[] counts = new long[total];
        for (int start = 0; start < total; start += BATCH_CHUNK) {
            int end = Math.min(total, start + BATCH_CHUNK);
            try {
                for (int i = start; i < end; i++) {
                    out.write(Protocol.bind("", statements.get(i), rows.get(i)));
                    out.write(Protocol.execute("", 0));
                }
                out.write(Protocol.sync());
                out.flush();
            } catch (IOException e) {
                throw ioError(e);
            }
            int done = start;
            SQLException error = null;
            try {
                while (true) {
                    Protocol.Message msg = Protocol.readMessage(in);
                    if (msg.type == Protocol.B_COMMAND_COMPLETE) {
                        if (error == null && done < end) {
                            counts[done++] = NusaStatement.affected(msg.reader().str());
                        }
                    } else if (msg.type == Protocol.B_ERROR) {
                        if (error == null) {
                            error = serverError(msg);
                        }
                    } else if (msg.type == Protocol.B_NOTIFICATION) {
                        notifications.addLast(decodeNotification(msg.reader()));
                    } else if (msg.type == Protocol.B_READY) {
                        break;
                    }
                }
            } catch (IOException e) {
                throw ioError(e);
            }
            if (error != null) {
                throw new java.sql.BatchUpdateException("nusadb: batch entry " + done + " failed: " + error.getMessage(),
                        error.getSQLState(), error.getErrorCode(), java.util.Arrays.copyOf(counts, done), error);
            }
        }
        return counts;
    }

    /**
     * Runs a {@code Statement} batch pipelined: up to {@link #BATCH_CHUNK} simple queries go out
     * before their replies (one ReadyForQuery each) are read. Returns one update count per query;
     * the first failure is thrown as a {@link java.sql.BatchUpdateException} with the counts of the
     * queries before it, after every reply of its chunk has been read.
     */
    synchronized long[] simpleBatch(List<String> queries) throws SQLException {
        ensureUsable();
        int total = queries.size();
        long[] counts = new long[total];
        for (int start = 0; start < total; start += BATCH_CHUNK) {
            int end = Math.min(total, start + BATCH_CHUNK);
            try {
                for (int i = start; i < end; i++) {
                    out.write(Protocol.query(queries.get(i)));
                }
                out.flush();
            } catch (IOException e) {
                throw ioError(e);
            }
            SQLException error = null;
            int failed = -1;
            try {
                int i = start;
                String tag = null;
                while (i < end) {
                    Protocol.Message msg = Protocol.readMessage(in);
                    if (msg.type == Protocol.B_COMMAND_COMPLETE) {
                        tag = msg.reader().str();
                    } else if (msg.type == Protocol.B_ERROR) {
                        if (error == null) {
                            error = serverError(msg);
                            failed = i;
                        }
                    } else if (msg.type == Protocol.B_NOTIFICATION) {
                        notifications.addLast(decodeNotification(msg.reader()));
                    } else if (msg.type == Protocol.B_READY) {
                        counts[i++] = NusaStatement.affected(tag);
                        tag = null;
                    }
                }
            } catch (IOException e) {
                throw ioError(e);
            }
            if (error != null) {
                throw new java.sql.BatchUpdateException("nusadb: batch entry " + failed + " failed: " + error.getMessage(),
                        error.getSQLState(), error.getErrorCode(), java.util.Arrays.copyOf(counts, failed), error);
            }
        }
        return counts;
    }

    // --- A result read from the socket a chunk at a time (Statement.setFetchSize) ---

    /** The result whose rows are still on the socket; null when none is. */
    private RowStream open;

    /**
     * A query result read lazily. The server sends the rows of a query (a simple Query, or an
     * Execute without a row limit) as it produces them, and they are read from the socket as the
     * result set asks for them; TCP flow control holds the server back meanwhile. An Execute with a
     * row limit is not used: the server materializes the whole result for it first.
     *
     * <p>While a stream is open the connection's socket belongs to it, so any other exchange first
     * reads the stream's remaining rows into memory ({@link #settle}) and the stream goes on from
     * there.
     *
     * <p>A stream closed before its end is cancelled on the server rather than read to the end.
     * Inside a transaction a cancelled statement would abort the transaction, so the query runs
     * after a savepoint of the driver's own: rolling back to it undoes only the cancelled query,
     * because a stream still on the socket means nothing else ran since. The savepoint is released
     * when the stream ends; it is left alone once another statement has run in between.
     */
    final class RowStream {
        final List<String> columns;
        final List<String> columnTypes;
        private final java.util.ArrayDeque<List<byte[]>> ahead = new java.util.ArrayDeque<>();
        /** Rows, or the messages that end the result, are still unread on the socket. */
        private boolean onSocket;
        private String tag;
        private SQLException error;
        /** The driver's savepoint before the query (inside a transaction), or null. */
        private final String savepoint;
        /** Another exchange has run since the query started (its rows were read ahead). */
        private boolean interrupted;

        RowStream(List<String> columns, List<String> columnTypes, boolean onSocket, String savepoint) {
            this.columns = columns;
            this.columnTypes = columnTypes;
            this.onSocket = onSocket;
            this.savepoint = savepoint;
        }

        /** Up to {@code n} rows; empty once the result is exhausted. A server error ends it, thrown here. */
        List<List<byte[]>> next(int n) throws SQLException {
            synchronized (Wire.this) {
                List<List<byte[]>> rows = new ArrayList<>(Math.min(n, 4096));
                while (rows.size() < n && !ahead.isEmpty()) {
                    rows.add(ahead.poll());
                }
                List<byte[]> row;
                while (rows.size() < n && onSocket && (row = readRow()) != null) {
                    rows.add(row);
                }
                if (rows.isEmpty() && error != null) {
                    SQLException e = error;
                    error = null;
                    throw e;
                }
                return rows;
            }
        }

        boolean exhausted() {
            synchronized (Wire.this) {
                return !onSocket && ahead.isEmpty() && error == null;
            }
        }

        /** The command tag, once the result has been read to its end. */
        String tag() {
            return tag;
        }

        /** One row from the socket; null after reading the end of the result up to ReadyForQuery. */
        private List<byte[]> readRow() throws SQLException {
            try {
                while (true) {
                    Protocol.Message msg = Protocol.readMessage(in);
                    switch (msg.type) {
                        case Protocol.B_DATA_ROW:
                            return msg.reader().fields();
                        case Protocol.B_COMMAND_COMPLETE:
                            tag = msg.reader().str();
                            break;
                        case Protocol.B_ERROR:
                            error = serverError(msg);
                            break;
                        case Protocol.B_NOTIFICATION:
                            notifications.addLast(decodeNotification(msg.reader()));
                            break;
                        case Protocol.B_READY:
                            finish();
                            return null;
                        default:
                            break;
                    }
                }
            } catch (IOException e) {
                finish();
                throw ioError(e);
            }
        }

        private void finish() {
            onSocket = false;
            if (open == this) {
                open = null;
            }
            // Ended with nothing run in between and no error: the savepoint surely exists.
            if (savepoint != null && !interrupted && error == null && !broken) {
                quietly("RELEASE SAVEPOINT " + savepoint);
            }
        }

        /** Reads every row left on the socket into memory, so the connection can serve something else. */
        void bufferRest() throws SQLException {
            interrupted = true;
            List<byte[]> row;
            while (onSocket && (row = readRow()) != null) {
                ahead.add(row);
            }
        }

        /**
         * The result set was closed before its end: a query still sending rows is cancelled and
         * its remaining messages read, and inside a transaction the cancelled query is rolled back
         * to the driver's savepoint, so the transaction goes on.
         */
        void discard() throws SQLException {
            synchronized (Wire.this) {
                ahead.clear();
                if (onSocket) {
                    try {
                        cancel();
                    } catch (SQLException ignored) {
                        // no cancel key: the rows are read to the end instead
                    }
                    while (onSocket) {
                        readRow();
                    }
                    if (savepoint != null && error != null && !interrupted && !broken) {
                        quietly("ROLLBACK TO SAVEPOINT " + savepoint);
                        quietly("RELEASE SAVEPOINT " + savepoint);
                    }
                }
                error = null;
            }
        }
    }

    /** Runs a statement of the driver's own, whose failure has nothing to report to. */
    private void quietly(String sql) {
        try {
            send(Protocol.query(sql));
            collect();
        } catch (IOException e) {
            ioError(e);
        } catch (SQLException ignored) {
            // e.g. the savepoint is gone with a transaction that ended
        }
    }

    /** Before another exchange: the open stream's remaining rows are read into memory. */
    private void settle() throws SQLException {
        RowStream stream = open;
        if (stream != null) {
            stream.bufferRest();
            open = null;
        }
    }

    /**
     * A simple query whose rows are read as the result set asks for them (see {@link RowStream}).
     * {@code inTransaction}: a savepoint is set first, in the same round trip.
     */
    synchronized RowStream streamQuery(String sql, boolean inTransaction) throws SQLException {
        ensureUsable();
        String savepoint = inTransaction ? "nusa_stream_" + (++counter) : null;
        try {
            if (savepoint != null) {
                out.write(Protocol.query("SAVEPOINT " + savepoint));
            }
            out.write(Protocol.query(sql));
            out.flush();
        } catch (IOException e) {
            throw ioError(e);
        }
        return startStream(savepoint);
    }

    /** A prepared statement run without a row limit, its rows read as the result set asks for them. */
    synchronized RowStream streamPrepared(String statement, List<byte[]> params, boolean inTransaction) throws SQLException {
        ensureUsable();
        String savepoint = inTransaction ? "nusa_stream_" + (++counter) : null;
        try {
            if (savepoint != null) {
                out.write(Protocol.query("SAVEPOINT " + savepoint));
            }
            out.write(Protocol.bind("", statement, params));
            out.write(Protocol.describePortal(""));
            out.write(Protocol.execute("", 0));
            out.write(Protocol.sync());
            out.flush();
        } catch (IOException e) {
            throw ioError(e);
        }
        return startStream(savepoint);
    }

    /**
     * Reads up to the row description: an open stream of the rows, or for a statement without rows
     * (or one that failed before any) the finished result.
     */
    private RowStream startStream(String savepoint) throws SQLException {
        if (savepoint != null) {
            collect(); // the savepoint's own reply
        }
        String tag = null;
        SQLException error = null;
        try {
            while (true) {
                Protocol.Message msg = Protocol.readMessage(in);
                Protocol.Reader r = msg.reader();
                switch (msg.type) {
                    case Protocol.B_ROW_DESCRIPTION:
                    case Protocol.B_ROW_DESCRIPTION_TYPED: {
                        boolean typed = msg.type == Protocol.B_ROW_DESCRIPTION_TYPED;
                        int n = r.u16();
                        List<String> columns = new ArrayList<>(n);
                        List<String> types = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            columns.add(r.str());
                            types.add(typed ? Protocol.typeName(r.u8()) : null);
                        }
                        RowStream stream = new RowStream(columns, types, true, savepoint);
                        open = stream;
                        return stream;
                    }
                    case Protocol.B_COMMAND_COMPLETE:
                        tag = r.str();
                        break;
                    case Protocol.B_ERROR:
                        error = serverError(msg);
                        break;
                    case Protocol.B_NOTIFICATION:
                        notifications.addLast(decodeNotification(r));
                        break;
                    case Protocol.B_READY: {
                        if (error != null) {
                            throw error;
                        }
                        if (savepoint != null) {
                            quietly("RELEASE SAVEPOINT " + savepoint);
                        }
                        RowStream done = new RowStream(new ArrayList<>(), new ArrayList<>(), false, null);
                        done.tag = tag;
                        return done;
                    }
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            throw ioError(e);
        }
    }

    /** Free a prepared statement on the server ({@code PreparedStatement.close}). */
    synchronized void closeStatement(String statement) throws SQLException {
        if (broken) {
            return; // the server-side session is gone with the socket
        }
        settle();
        try {
            send(Protocol.close(Protocol.TARGET_STATEMENT, statement));
            send(Protocol.sync());
        } catch (IOException e) {
            throw ioError(e);
        }
        collect();
    }

    /**
     * Bulk-load via {@code COPY ... FROM STDIN} (§12.1): send the COPY statement, stream {@code source}
     * (already in the server's text format: tab-delimited, {@code \N} for NULL) in 64 KiB
     * {@code CopyData} chunks, finish with {@code CopyDone}, and return the loaded row count. A read
     * error on {@code source} sends {@code CopyFail} and leaves the connection ready; a refused COPY
     * surfaces as the server's error.
     */
    synchronized long copyIn(String sql, java.io.InputStream source) throws SQLException {
        ensureUsable();
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
            throw ioError(e);
        }
    }

    /**
     * Bulk-export via {@code COPY ... TO STDOUT} (§12.2): send the COPY statement, write every
     * {@code CopyData} chunk into {@code sink}, and return the exported row count.
     */
    synchronized long copyOut(String sql, OutputStream sink) throws SQLException {
        ensureUsable();
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
            throw ioError(e);
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
        if (broken) {
            closeQuietly(socket);
            return;
        }
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
