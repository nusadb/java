package com.nusadb.jdbc;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Arrays;
import java.util.Map;

/**
 * A {@link java.sql.Array} returned by {@link NusaConnection#createArrayOf} or read from an array
 * column. It holds the element type name and the elements; {@link NusaPreparedStatement} renders
 * it to the SQL array literal ({@code {a,b}}) the server coerces into an array column.
 */
final class NusaArray implements Array {
    private final String baseTypeName;
    private Object[] elements;

    NusaArray(String baseTypeName, Object[] elements) {
        this.baseTypeName = baseTypeName == null ? "text" : baseTypeName;
        this.elements = elements == null ? new Object[0] : elements.clone();
    }

    /**
     * Parses the server's text form of an array, {@code {a,"b c",NULL,{1,2}}}, into its elements:
     * a {@code String} per element, {@code null} for {@code NULL}, and an {@code Object[]} per
     * nested array. The server does not report the element type, so the values stay text.
     */
    static Object[] parse(String literal) throws SQLException {
        int[] pos = {0};
        Object[] out = parseLevel(literal, pos);
        skipSpaces(literal, pos);
        if (pos[0] != literal.length()) {
            throw malformed(literal);
        }
        return out;
    }

    private static Object[] parseLevel(String s, int[] pos) throws SQLException {
        skipSpaces(s, pos);
        if (pos[0] >= s.length() || s.charAt(pos[0]) != '{') {
            throw malformed(s);
        }
        pos[0]++;
        java.util.List<Object> out = new java.util.ArrayList<>();
        skipSpaces(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return out.toArray();
        }
        while (true) {
            skipSpaces(s, pos);
            if (pos[0] >= s.length()) {
                throw malformed(s);
            }
            char c = s.charAt(pos[0]);
            if (c == '{') {
                out.add(parseLevel(s, pos));
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                pos[0]++;
                while (true) {
                    if (pos[0] >= s.length()) {
                        throw malformed(s);
                    }
                    char q = s.charAt(pos[0]++);
                    if (q == '\\' && pos[0] < s.length()) {
                        sb.append(s.charAt(pos[0]++));
                    } else if (q == '"') {
                        break;
                    } else {
                        sb.append(q);
                    }
                }
                out.add(sb.toString());
            } else {
                int start = pos[0];
                while (pos[0] < s.length() && s.charAt(pos[0]) != ',' && s.charAt(pos[0]) != '}') {
                    pos[0]++;
                }
                String raw = s.substring(start, pos[0]).trim();
                out.add("NULL".equalsIgnoreCase(raw) ? null : raw);
            }
            skipSpaces(s, pos);
            if (pos[0] >= s.length()) {
                throw malformed(s);
            }
            char sep = s.charAt(pos[0]++);
            if (sep == '}') {
                return out.toArray();
            }
            if (sep != ',') {
                throw malformed(s);
            }
        }
    }

    private static void skipSpaces(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static SQLException malformed(String literal) {
        return new SQLException("nusadb: malformed array value: " + literal, "22P02");
    }

    @Override
    public String getBaseTypeName() {
        return baseTypeName;
    }

    @Override
    public int getBaseType() {
        return Types.OTHER;
    }

    @Override
    public Object getArray() throws SQLException {
        ensureOpen();
        return elements.clone();
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        return getArray();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        ensureOpen();
        if (index < 1 || count < 0 || index - 1 + count > elements.length) {
            throw new SQLException("nusadb: array slice out of range", "22003");
        }
        return Arrays.copyOfRange(elements, (int) (index - 1), (int) (index - 1) + count);
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        return getArray(index, count);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        throw new SQLFeatureNotSupportedException("nusadb: Array.getResultSet is not supported");
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException("nusadb: Array.getResultSet is not supported");
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        throw new SQLFeatureNotSupportedException("nusadb: Array.getResultSet is not supported");
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("nusadb: Array.getResultSet is not supported");
    }

    @Override
    public void free() {
        elements = new Object[0];
    }

    private void ensureOpen() throws SQLException {
        if (elements == null) {
            throw new SQLException("nusadb: array has been freed", "55000");
        }
    }
}
