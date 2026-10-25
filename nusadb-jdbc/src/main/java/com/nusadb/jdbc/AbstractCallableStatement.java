package com.nusadb.jdbc;

import java.sql.CallableStatement;
import java.sql.SQLException;

/**
 * A {@link CallableStatement} base whose own methods (OUT parameters, and every parameter by name)
 * throw {@link java.sql.SQLFeatureNotSupportedException}. {@link NusaCallableStatement} overrides
 * the supported ones: OUT parameters and their getters by index.
 */
abstract class AbstractCallableStatement extends NusaPreparedStatement implements CallableStatement {
    AbstractCallableStatement(NusaConnection connection, String sql) throws SQLException {
        super(connection, sql);
    }

    @Override @Deprecated public java.math.BigDecimal getBigDecimal(int a, int b) throws SQLException { throw unsupported(); }
    @Override public <T> T getObject(String a, Class<T> b) throws SQLException { throw unsupported(); }
    @Override public <T> T getObject(int a, Class<T> b) throws SQLException { throw unsupported(); }
    @Override public Object getObject(String a) throws SQLException { throw unsupported(); }
    @Override public Object getObject(String a, java.util.Map<String, Class<?>> b) throws SQLException { throw unsupported(); }
    @Override public Object getObject(int a) throws SQLException { throw unsupported(); }
    @Override public Object getObject(int a, java.util.Map<String, Class<?>> b) throws SQLException { throw unsupported(); }
    @Override public String getNString(String a) throws SQLException { throw unsupported(); }
    @Override public String getNString(int a) throws SQLException { throw unsupported(); }
    @Override public String getString(String a) throws SQLException { throw unsupported(); }
    @Override public String getString(int a) throws SQLException { throw unsupported(); }
    @Override public boolean getBoolean(String a) throws SQLException { throw unsupported(); }
    @Override public boolean getBoolean(int a) throws SQLException { throw unsupported(); }
    @Override public boolean wasNull() throws SQLException { throw unsupported(); }
    @Override public byte getByte(String a) throws SQLException { throw unsupported(); }
    @Override public byte getByte(int a) throws SQLException { throw unsupported(); }
    @Override public byte[] getBytes(String a) throws SQLException { throw unsupported(); }
    @Override public byte[] getBytes(int a) throws SQLException { throw unsupported(); }
    @Override public double getDouble(String a) throws SQLException { throw unsupported(); }
    @Override public double getDouble(int a) throws SQLException { throw unsupported(); }
    @Override public float getFloat(String a) throws SQLException { throw unsupported(); }
    @Override public float getFloat(int a) throws SQLException { throw unsupported(); }
    @Override public int getInt(String a) throws SQLException { throw unsupported(); }
    @Override public int getInt(int a) throws SQLException { throw unsupported(); }
    @Override public java.io.Reader getCharacterStream(String a) throws SQLException { throw unsupported(); }
    @Override public java.io.Reader getCharacterStream(int a) throws SQLException { throw unsupported(); }
    @Override public java.io.Reader getNCharacterStream(String a) throws SQLException { throw unsupported(); }
    @Override public java.io.Reader getNCharacterStream(int a) throws SQLException { throw unsupported(); }
    @Override public java.math.BigDecimal getBigDecimal(String a) throws SQLException { throw unsupported(); }
    @Override public java.math.BigDecimal getBigDecimal(int a) throws SQLException { throw unsupported(); }
    @Override public java.net.URL getURL(String a) throws SQLException { throw unsupported(); }
    @Override public java.net.URL getURL(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Array getArray(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Array getArray(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Blob getBlob(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Blob getBlob(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Clob getClob(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Clob getClob(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Date getDate(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Date getDate(String a, java.util.Calendar b) throws SQLException { throw unsupported(); }
    @Override public java.sql.Date getDate(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Date getDate(int a, java.util.Calendar b) throws SQLException { throw unsupported(); }
    @Override public java.sql.NClob getNClob(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.NClob getNClob(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Ref getRef(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Ref getRef(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.RowId getRowId(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.RowId getRowId(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.SQLXML getSQLXML(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.SQLXML getSQLXML(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Time getTime(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Time getTime(String a, java.util.Calendar b) throws SQLException { throw unsupported(); }
    @Override public java.sql.Time getTime(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Time getTime(int a, java.util.Calendar b) throws SQLException { throw unsupported(); }
    @Override public java.sql.Timestamp getTimestamp(String a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Timestamp getTimestamp(String a, java.util.Calendar b) throws SQLException { throw unsupported(); }
    @Override public java.sql.Timestamp getTimestamp(int a) throws SQLException { throw unsupported(); }
    @Override public java.sql.Timestamp getTimestamp(int a, java.util.Calendar b) throws SQLException { throw unsupported(); }
    @Override public long getLong(String a) throws SQLException { throw unsupported(); }
    @Override public long getLong(int a) throws SQLException { throw unsupported(); }
    @Override public short getShort(String a) throws SQLException { throw unsupported(); }
    @Override public short getShort(int a) throws SQLException { throw unsupported(); }
    @Override public void registerOutParameter(String a, int b) throws SQLException { throw unsupported(); }
    @Override public void registerOutParameter(String a, int b, String c) throws SQLException { throw unsupported(); }
    @Override public void registerOutParameter(String a, int b, int c) throws SQLException { throw unsupported(); }
    @Override public void registerOutParameter(int a, int b) throws SQLException { throw unsupported(); }
    @Override public void registerOutParameter(int a, int b, String c) throws SQLException { throw unsupported(); }
    @Override public void registerOutParameter(int a, int b, int c) throws SQLException { throw unsupported(); }
    @Override public void setAsciiStream(String a, java.io.InputStream b) throws SQLException { throw unsupported(); }
    @Override public void setAsciiStream(String a, java.io.InputStream b, int c) throws SQLException { throw unsupported(); }
    @Override public void setAsciiStream(String a, java.io.InputStream b, long c) throws SQLException { throw unsupported(); }
    @Override public void setBigDecimal(String a, java.math.BigDecimal b) throws SQLException { throw unsupported(); }
    @Override public void setBinaryStream(String a, java.io.InputStream b) throws SQLException { throw unsupported(); }
    @Override public void setBinaryStream(String a, java.io.InputStream b, int c) throws SQLException { throw unsupported(); }
    @Override public void setBinaryStream(String a, java.io.InputStream b, long c) throws SQLException { throw unsupported(); }
    @Override public void setBlob(String a, java.io.InputStream b) throws SQLException { throw unsupported(); }
    @Override public void setBlob(String a, java.io.InputStream b, long c) throws SQLException { throw unsupported(); }
    @Override public void setBlob(String a, java.sql.Blob b) throws SQLException { throw unsupported(); }
    @Override public void setBoolean(String a, boolean b) throws SQLException { throw unsupported(); }
    @Override public void setByte(String a, byte b) throws SQLException { throw unsupported(); }
    @Override public void setBytes(String a, byte[] b) throws SQLException { throw unsupported(); }
    @Override public void setCharacterStream(String a, java.io.Reader b) throws SQLException { throw unsupported(); }
    @Override public void setCharacterStream(String a, java.io.Reader b, int c) throws SQLException { throw unsupported(); }
    @Override public void setCharacterStream(String a, java.io.Reader b, long c) throws SQLException { throw unsupported(); }
    @Override public void setClob(String a, java.io.Reader b) throws SQLException { throw unsupported(); }
    @Override public void setClob(String a, java.io.Reader b, long c) throws SQLException { throw unsupported(); }
    @Override public void setClob(String a, java.sql.Clob b) throws SQLException { throw unsupported(); }
    @Override public void setDate(String a, java.sql.Date b) throws SQLException { throw unsupported(); }
    @Override public void setDate(String a, java.sql.Date b, java.util.Calendar c) throws SQLException { throw unsupported(); }
    @Override public void setDouble(String a, double b) throws SQLException { throw unsupported(); }
    @Override public void setFloat(String a, float b) throws SQLException { throw unsupported(); }
    @Override public void setInt(String a, int b) throws SQLException { throw unsupported(); }
    @Override public void setLong(String a, long b) throws SQLException { throw unsupported(); }
    @Override public void setNCharacterStream(String a, java.io.Reader b) throws SQLException { throw unsupported(); }
    @Override public void setNCharacterStream(String a, java.io.Reader b, long c) throws SQLException { throw unsupported(); }
    @Override public void setNClob(String a, java.io.Reader b) throws SQLException { throw unsupported(); }
    @Override public void setNClob(String a, java.io.Reader b, long c) throws SQLException { throw unsupported(); }
    @Override public void setNClob(String a, java.sql.NClob b) throws SQLException { throw unsupported(); }
    @Override public void setNString(String a, String b) throws SQLException { throw unsupported(); }
    @Override public void setNull(String a, int b) throws SQLException { throw unsupported(); }
    @Override public void setNull(String a, int b, String c) throws SQLException { throw unsupported(); }
    @Override public void setObject(String a, Object b) throws SQLException { throw unsupported(); }
    @Override public void setObject(String a, Object b, int c) throws SQLException { throw unsupported(); }
    @Override public void setObject(String a, Object b, int c, int d) throws SQLException { throw unsupported(); }
    @Override public void setRowId(String a, java.sql.RowId b) throws SQLException { throw unsupported(); }
    @Override public void setSQLXML(String a, java.sql.SQLXML b) throws SQLException { throw unsupported(); }
    @Override public void setShort(String a, short b) throws SQLException { throw unsupported(); }
    @Override public void setString(String a, String b) throws SQLException { throw unsupported(); }
    @Override public void setTime(String a, java.sql.Time b) throws SQLException { throw unsupported(); }
    @Override public void setTime(String a, java.sql.Time b, java.util.Calendar c) throws SQLException { throw unsupported(); }
    @Override public void setTimestamp(String a, java.sql.Timestamp b) throws SQLException { throw unsupported(); }
    @Override public void setTimestamp(String a, java.sql.Timestamp b, java.util.Calendar c) throws SQLException { throw unsupported(); }
    @Override public void setURL(String a, java.net.URL b) throws SQLException { throw unsupported(); }
}
