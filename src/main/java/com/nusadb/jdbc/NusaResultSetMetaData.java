package com.nusadb.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

/**
 * {@link ResultSetMetaData} backed by the wire's per-column type names (protocol 1.1). A column with
 * a known type reports its {@link java.sql.Types} code and NusaDB type name; an untyped column (a 1.0
 * server, or a metadata result) falls back to {@code VARCHAR} / {@code "text"}. Values are always
 * delivered in text format.
 */
final class NusaResultSetMetaData implements ResultSetMetaData {
    private final List<String> columns;
    private final List<String> columnTypes;

    NusaResultSetMetaData(List<String> columns, List<String> columnTypes) {
        this.columns = columns;
        this.columnTypes = columnTypes;
    }

    /** The wire type name for a 1-based column, or {@code null} if untyped/unavailable. */
    private String typeName(int column) {
        if (columnTypes == null || column < 1 || column > columnTypes.size()) {
            return null;
        }
        return columnTypes.get(column - 1);
    }

    private void check(int column) throws SQLException {
        if (column < 1 || column > columns.size()) {
            throw new SQLException("nusadb: column index out of range: " + column, "07009");
        }
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        check(column);
        return columns.get(column - 1);
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        return getColumnLabel(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        check(column);
        return Protocol.sqlType(typeName(column));
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        check(column);
        String name = typeName(column);
        return name != null ? name : "text";
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        check(column);
        return "java.lang.String";
    }

    @Override
    public int isNullable(int column) throws SQLException {
        check(column);
        return columnNullableUnknown;
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        check(column);
        return 0;
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        check(column);
        return 0;
    }

    @Override
    public int getScale(int column) throws SQLException {
        check(column);
        return 0;
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public String getTableName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("nusadb: not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
