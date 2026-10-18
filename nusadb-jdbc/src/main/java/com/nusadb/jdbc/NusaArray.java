package com.nusadb.jdbc;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Arrays;
import java.util.Map;

/**
 * A {@link java.sql.Array} returned by {@link NusaConnection#createArrayOf}. It holds the element
 * type name and the elements; {@link NusaPreparedStatement} renders it to the SQL array literal
 * ({@code {a,b}}) the server coerces into an array column.
 */
final class NusaArray implements Array {
    private final String baseTypeName;
    private Object[] elements;

    NusaArray(String baseTypeName, Object[] elements) {
        this.baseTypeName = baseTypeName == null ? "text" : baseTypeName;
        this.elements = elements == null ? new Object[0] : elements.clone();
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
