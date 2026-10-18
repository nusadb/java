package com.nusadb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

/**
 * A {@link PreparedStatement} base whose parameter-setter and stream surface throws
 * {@link java.sql.SQLFeatureNotSupportedException}. {@link NusaPreparedStatement} overrides the
 * supported setters and the no-argument execute methods.
 */
abstract class AbstractPreparedStatement extends NusaStatement implements PreparedStatement {
    AbstractPreparedStatement(NusaConnection connection) {
        super(connection);
    }

    @Override public ResultSet executeQuery() throws SQLException { throw unsupported(); }
    @Override public int executeUpdate() throws SQLException { throw unsupported(); }
    @Override public boolean execute() throws SQLException { throw unsupported(); }
    @Override public void addBatch() throws SQLException { throw unsupported(); }
    @Override public void clearParameters() throws SQLException { throw unsupported(); }
    @Override public void setNull(int parameterIndex, int sqlType) throws SQLException { throw unsupported(); }
    @Override public void setBoolean(int parameterIndex, boolean x) throws SQLException { throw unsupported(); }
    @Override public void setByte(int parameterIndex, byte x) throws SQLException { throw unsupported(); }
    @Override public void setShort(int parameterIndex, short x) throws SQLException { throw unsupported(); }
    @Override public void setInt(int parameterIndex, int x) throws SQLException { throw unsupported(); }
    @Override public void setLong(int parameterIndex, long x) throws SQLException { throw unsupported(); }
    @Override public void setFloat(int parameterIndex, float x) throws SQLException { throw unsupported(); }
    @Override public void setDouble(int parameterIndex, double x) throws SQLException { throw unsupported(); }
    @Override public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException { throw unsupported(); }
    @Override public void setString(int parameterIndex, String x) throws SQLException { throw unsupported(); }
    @Override public void setBytes(int parameterIndex, byte[] x) throws SQLException { throw unsupported(); }
    @Override public void setDate(int parameterIndex, Date x) throws SQLException { throw unsupported(); }
    @Override public void setTime(int parameterIndex, Time x) throws SQLException { throw unsupported(); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException { throw unsupported(); }
    @Override public void setObject(int parameterIndex, Object x) throws SQLException { throw unsupported(); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException { throw unsupported(); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException { throw unsupported(); }
    @Override @Deprecated public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException { throw unsupported(); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException { throw unsupported(); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException { throw unsupported(); }
    @Override public void setRef(int parameterIndex, Ref x) throws SQLException { throw unsupported(); }
    @Override public void setBlob(int parameterIndex, Blob x) throws SQLException { throw unsupported(); }
    @Override public void setClob(int parameterIndex, Clob x) throws SQLException { throw unsupported(); }
    @Override public void setArray(int parameterIndex, Array x) throws SQLException { throw unsupported(); }
    @Override public ResultSetMetaData getMetaData() throws SQLException { return null; }
    @Override public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException { throw unsupported(); }
    @Override public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException { throw unsupported(); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException { throw unsupported(); }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException { throw unsupported(); }
    @Override public void setURL(int parameterIndex, URL x) throws SQLException { throw unsupported(); }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { throw unsupported(); }
    @Override public void setRowId(int parameterIndex, RowId x) throws SQLException { throw unsupported(); }
    @Override public void setNString(int parameterIndex, String value) throws SQLException { throw unsupported(); }
    @Override public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException { throw unsupported(); }
    @Override public void setNClob(int parameterIndex, NClob value) throws SQLException { throw unsupported(); }
    @Override public void setClob(int parameterIndex, Reader reader, long length) throws SQLException { throw unsupported(); }
    @Override public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException { throw unsupported(); }
    @Override public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException { throw unsupported(); }
    @Override public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException { throw unsupported(); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException { throw unsupported(); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException { throw unsupported(); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException { throw unsupported(); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException { throw unsupported(); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException { throw unsupported(); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException { throw unsupported(); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException { throw unsupported(); }
    @Override public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException { throw unsupported(); }
    @Override public void setClob(int parameterIndex, Reader reader) throws SQLException { throw unsupported(); }
    @Override public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException { throw unsupported(); }
    @Override public void setNClob(int parameterIndex, Reader reader) throws SQLException { throw unsupported(); }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException { throw unsupported(); }
    @Override public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException { throw unsupported(); }
}
