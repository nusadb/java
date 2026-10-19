package com.nusadb.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-level Nusa Wire Protocol codec (docs/wire-protocol.md, PROTOCOL_VERSION 1.1).
 *
 * <p>A frame is {@code [type:u8][len:u32][payload]}; {@code len} is the total size including the
 * 5-byte header. Big-endian throughout; strings are {@code [len:u32][utf8 bytes]}, not
 * null-terminated.
 */
final class Protocol {
    static final int PROTOCOL_MAGIC = 0x4E555341; // "NUSA"
    static final int PROTOCOL_MAJOR = 1;
    // Request minor 1 to receive the typed RowDescription (per-column type tags). A 1.0 server
    // ignores it and answers with the classic untyped form, which the driver still handles.
    static final int PROTOCOL_MINOR = 1;
    static final int HEADER_LEN = 5;
    static final long MAX_FRAME_LEN = 256L * 1024 * 1024;
    static final String SCRAM_MECHANISM = "SCRAM-SHA-256";

    // Frontend type bytes.
    static final byte T_STARTUP = 'S';
    static final byte T_QUERY = 'Q';
    static final byte T_PARSE = 'P';
    static final byte T_BIND = 'B';
    static final byte T_DESCRIBE = 'D';
    static final byte T_EXECUTE = 'E';
    static final byte T_SYNC = 'Y';
    static final byte T_CLOSE = 'C'; // free a prepared statement or portal: [target:u8][name:Str]
    static final byte T_SASL_INITIAL = 'p';
    static final byte T_SASL_RESPONSE = 'r';
    static final byte T_CANCEL = 'K';
    static final byte T_TERMINATE = 'X';
    static final byte T_COPY_DATA = 'd'; // COPY ... FROM STDIN data chunk (§12.1)
    static final byte T_COPY_DONE = 'c'; // end of the client's COPY data stream
    static final byte T_COPY_FAIL = 'f'; // abort the in-progress COPY: [message:Str]

    // Parameter type tags declared in Parse (§9.2). 0 lets the server infer the type from the value
    // text, which reads a numeric-looking string as a number (§10.4).
    static final byte TAG_INFER = 0x00;
    static final byte TAG_BOOL = 0x01;
    static final byte TAG_INT = 0x02;
    static final byte TAG_FLOAT = 0x03;
    static final byte TAG_NUMERIC = 0x04;
    static final byte TAG_TEXT = 0x05;
    static final byte TAG_BYTES = 0x06;
    static final byte TAG_UUID = 0x0D;

    /** {@code Close} / {@code Describe} targets. */
    static final byte TARGET_STATEMENT = 'S';
    static final byte TARGET_PORTAL = 'P';

    // Backend type bytes.
    static final byte B_AUTH = 'R';
    static final byte B_BACKEND_KEY = 'K';
    static final byte B_READY = 'Z';
    static final byte B_COMMAND_COMPLETE = 'C';
    static final byte B_ERROR = 'E';
    static final byte B_ROW_DESCRIPTION = 'T';
    static final byte B_ROW_DESCRIPTION_TYPED = 'y'; // protocol 1.1 (typed columns)
    static final byte B_DATA_ROW = 'D';
    static final byte B_COPY_IN_RESPONSE = 'G'; // server ready to receive COPY data (§12.1)
    static final byte B_COPY_OUT_RESPONSE = 'H'; // server about to send COPY data (§12.2)
    static final byte B_COPY_DATA = 'd'; // a COPY ... TO STDOUT data chunk
    static final byte B_COPY_DONE = 'c'; // end of the server's COPY data stream
    static final byte B_NOTIFICATION = 'A'; // async LISTEN/NOTIFY: [pid:u32][channel:str][payload:str]

    /**
     * Canonical type name for a {@code RowDescriptionTyped} type tag (protocol 1.1,
     * wire-protocol.md §9.2); an unknown/{@code 0x00} tag is {@code "UNKNOWN"} (treated as text).
     */
    static String typeName(int tag) {
        switch (tag) {
            case 0x01: return "BOOL";
            case 0x02: return "INT";
            case 0x03: return "FLOAT";
            case 0x04: return "NUMERIC";
            case 0x05: return "TEXT";
            case 0x06: return "BYTES";
            case 0x07: return "DATE";
            case 0x08: return "TIME";
            case 0x09: return "TIMETZ";
            case 0x0A: return "TIMESTAMP";
            case 0x0B: return "TIMESTAMPTZ";
            case 0x0C: return "INTERVAL";
            case 0x0D: return "UUID";
            case 0x0E: return "JSON";
            case 0x0F: return "ARRAY";
            case 0x10: return "VECTOR";
            default: return "UNKNOWN";
        }
    }

    /** Map a NusaDB type name to a {@link java.sql.Types} code (for {@code ResultSetMetaData}). */
    static int sqlType(String typeName) {
        if (typeName == null) {
            return Types.VARCHAR;
        }
        switch (typeName) {
            case "BOOL": return Types.BOOLEAN;
            case "INT": return Types.BIGINT;
            case "FLOAT": return Types.DOUBLE;
            case "NUMERIC": return Types.NUMERIC;
            case "BYTES": return Types.VARBINARY;
            case "DATE": return Types.DATE;
            case "TIME":
            case "TIMETZ": return Types.TIME;
            case "TIMESTAMP":
            case "TIMESTAMPTZ": return Types.TIMESTAMP;
            case "UUID":
            case "JSON":
            case "ARRAY":
            case "VECTOR":
            case "INTERVAL":
            case "TEXT": return Types.VARCHAR;
            default: return Types.VARCHAR;
        }
    }

    // Auth sub-codes.
    static final int AUTH_OK = 0;
    static final int AUTH_SASL = 10;
    static final int AUTH_SASL_CONTINUE = 11;
    static final int AUTH_SASL_FINAL = 12;

    private Protocol() {}

    /** Accumulates a message payload with the protocol's primitive encodings. */
    static final class Writer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Writer u8(int v) {
            buf.write(v & 0xFF);
            return this;
        }

        Writer u16(int v) {
            buf.write((v >>> 8) & 0xFF);
            buf.write(v & 0xFF);
            return this;
        }

        Writer u32(long v) {
            buf.write((int) ((v >>> 24) & 0xFF));
            buf.write((int) ((v >>> 16) & 0xFF));
            buf.write((int) ((v >>> 8) & 0xFF));
            buf.write((int) (v & 0xFF));
            return this;
        }

        Writer raw(byte[] b) {
            buf.write(b, 0, b.length);
            return this;
        }

        Writer str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            u32(b.length);
            return raw(b);
        }

        /** Encode a Fields list: [count:u16] then per field present byte + optional [len][bytes]. */
        Writer fields(List<byte[]> values) {
            u16(values.size());
            for (byte[] v : values) {
                if (v == null) {
                    u8(0);
                } else {
                    u8(1);
                    u32(v.length);
                    raw(v);
                }
            }
            return this;
        }

        byte[] frame(byte type) {
            byte[] payload = buf.toByteArray();
            int total = payload.length + HEADER_LEN;
            ByteArrayOutputStream out = new ByteArrayOutputStream(total);
            out.write(type);
            out.write((total >>> 24) & 0xFF);
            out.write((total >>> 16) & 0xFF);
            out.write((total >>> 8) & 0xFF);
            out.write(total & 0xFF);
            out.write(payload, 0, payload.length);
            return out.toByteArray();
        }
    }

    /** Reads the protocol's primitive encodings from a payload buffer. */
    static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf;
        }

        int u8() {
            return buf[pos++] & 0xFF;
        }

        int u16() {
            return (u8() << 8) | u8();
        }

        long u32() {
            return ((long) u8() << 24) | ((long) u8() << 16) | ((long) u8() << 8) | u8();
        }

        String str() {
            int n = (int) u32();
            String s = new String(buf, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        byte[] rest() {
            byte[] b = new byte[buf.length - pos];
            System.arraycopy(buf, pos, b, 0, b.length);
            pos = buf.length;
            return b;
        }

        /** Decode a Fields list; a null entry is SQL NULL. */
        List<byte[]> fields() {
            int n = u16();
            List<byte[]> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (u8() == 0) {
                    out.add(null);
                } else {
                    int len = (int) u32();
                    byte[] field = new byte[len];
                    System.arraycopy(buf, pos, field, 0, len);
                    pos += len;
                    out.add(field);
                }
            }
            return out;
        }
    }

    /** One decoded backend frame. */
    static final class Message {
        final byte type;
        final byte[] payload;

        Message(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        Reader reader() {
            return new Reader(payload);
        }
    }

    static Message readMessage(DataInputStream in) throws IOException {
        byte type = in.readByte();
        long total = ((long) (in.readByte() & 0xFF) << 24)
                | ((long) (in.readByte() & 0xFF) << 16)
                | ((long) (in.readByte() & 0xFF) << 8)
                | (in.readByte() & 0xFF);
        if (total < HEADER_LEN || total > MAX_FRAME_LEN) {
            throw new IOException("nusadb: invalid frame length " + total);
        }
        byte[] payload = new byte[(int) (total - HEADER_LEN)];
        in.readFully(payload);
        return new Message(type, payload);
    }

    // --- frontend message builders ---

    static byte[] startup(String user, String database) {
        return new Writer().u32(PROTOCOL_MAGIC).u16(PROTOCOL_MAJOR).u16(PROTOCOL_MINOR)
                .str(user).str(database).frame(T_STARTUP);
    }

    static byte[] query(String sql) {
        return new Writer().str(sql).frame(T_QUERY);
    }

    static byte[] parse(String name, String sql) {
        return parse(name, sql, new byte[0]);
    }

    /** Parse with each placeholder's declared type tag, {@code $1} first. */
    static byte[] parse(String name, String sql, byte[] paramTypes) {
        return new Writer().str(name).str(sql).u16(paramTypes.length).raw(paramTypes).frame(T_PARSE);
    }

    static byte[] bind(String portal, String statement, List<byte[]> values) {
        return new Writer().str(portal).str(statement).fields(values).u16(0).frame(T_BIND);
    }

    static byte[] describePortal(String name) {
        return new Writer().u8('P').str(name).frame(T_DESCRIBE);
    }

    static byte[] execute(String portal, long maxRows) {
        return new Writer().str(portal).u32(maxRows).frame(T_EXECUTE);
    }

    static byte[] sync() {
        return new Writer().frame(T_SYNC);
    }

    static byte[] close(byte target, String name) {
        return new Writer().u8(target).str(name).frame(T_CLOSE);
    }

    static byte[] terminate() {
        return new Writer().frame(T_TERMINATE);
    }

    static byte[] saslInitial(String mechanism, byte[] data) {
        return new Writer().str(mechanism).u32(data.length).raw(data).frame(T_SASL_INITIAL);
    }

    static byte[] saslResponse(byte[] data) {
        return new Writer().raw(data).frame(T_SASL_RESPONSE);
    }

    static byte[] cancel(long pid, long secret) {
        return new Writer().u32(pid).u32(secret).frame(T_CANCEL);
    }

    static byte[] copyData(byte[] data) {
        return new Writer().raw(data).frame(T_COPY_DATA);
    }

    static byte[] copyDone() {
        return new Writer().frame(T_COPY_DONE);
    }

    static byte[] copyFail(String message) {
        return new Writer().str(message).frame(T_COPY_FAIL);
    }

    static void write(OutputStream out, byte[] frame) throws IOException {
        out.write(frame);
        out.flush();
    }
}
