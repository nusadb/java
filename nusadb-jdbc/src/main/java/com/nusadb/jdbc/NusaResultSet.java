package com.nusadb.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * A {@link java.sql.ResultSet} over a collected {@link Wire.Result}, or over an open portal read one
 * chunk at a time ({@link NusaCursor}). With a {@link RowUpdater} it is updatable
 * ({@code CONCUR_UPDATABLE}): its rows are then all at hand and changes are written through it.
 */
final class NusaResultSet extends UpdatingResultSet {
    private final NusaStatement statement;
    private final List<String> columns;
    private final List<String> columnTypes;
    /** The rows at hand: the whole result, or the current chunk of a cursor. */
    private List<List<byte[]>> rows;
    /** The portal the next chunks come from; null when every row is in {@link #rows}. */
    private final NusaCursor cursor;
    /** -1 before the first row, {@code rows.size()} after the last. */
    private int rowIndex = -1;
    /** {@link ResultSet#TYPE_SCROLL_INSENSITIVE}: every row is at hand and the position can move freely. */
    private final boolean scrollable;
    private int fetchDirection = FETCH_FORWARD;
    private boolean lastWasNull;
    private boolean closed;
    /** Writes changes back to the table; null for a read-only result set. */
    private RowUpdater updater;
    /** Changes to the current row, or the insert row, by 0-based column. */
    private final java.util.Map<Integer, Object> changes = new java.util.LinkedHashMap<>();
    private boolean onInsertRow;

    NusaResultSet(NusaStatement statement, Wire.Result result) {
        this(statement, result, 0);
    }

    /** {@code maxRows > 0} caps the rows visible through this result set ({@code Statement.setMaxRows}). */
    NusaResultSet(NusaStatement statement, Wire.Result result, int maxRows) {
        this.statement = statement;
        this.columns = result.columns;
        this.columnTypes = result.columnTypes;
        this.rows = maxRows > 0 && result.rows.size() > maxRows ? result.rows.subList(0, maxRows) : result.rows;
        this.cursor = null;
        this.scrollable = statement != null && statement.getResultSetType() == TYPE_SCROLL_INSENSITIVE;
    }

    /** A result set whose rows are read from {@code stream} one chunk at a time, by {@code cursor}. */
    NusaResultSet(NusaStatement statement, Wire.RowStream stream, NusaCursor cursor) throws SQLException {
        this.statement = statement;
        this.columns = stream.columns;
        this.columnTypes = stream.columnTypes;
        this.cursor = cursor;
        this.scrollable = false;
        this.rows = cursor.fetch();
    }

    /** Makes this result set updatable through {@code updater}. */
    void updatable(RowUpdater updater) {
        this.updater = updater;
        this.rows = new java.util.ArrayList<>(rows);
    }

    List<String> columnLabels() {
        return columns;
    }

    /** The cells of the current row, as received. */
    List<byte[]> currentCells() throws SQLException {
        ensureOpen();
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new SQLException("nusadb: no current row", "24000");
        }
        return rows.get(rowIndex);
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("nusadb: result set is closed", "24000");
        }
    }

    private byte[] cell(int columnIndex) throws SQLException {
        ensureOpen();
        if (onInsertRow || rowIndex < 0 || rowIndex >= rows.size()) {
            throw new SQLException("nusadb: no current row", "24000");
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("nusadb: column index out of range: " + columnIndex, "07009");
        }
        byte[] value = rows.get(rowIndex).get(columnIndex - 1);
        lastWasNull = value == null;
        return value;
    }

    private String text(int columnIndex) throws SQLException {
        byte[] b = cell(columnIndex);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        ensureOpen();
        int i = columns.indexOf(columnLabel);
        if (i < 0) {
            throw new SQLException("nusadb: no such column: " + columnLabel, "42703");
        }
        return i + 1;
    }

    @Override
    public boolean next() throws SQLException {
        ensureOpen();
        leaveInsertRow();
        if (rowIndex < rows.size()) {
            rowIndex++;
        }
        while (rowIndex >= rows.size() && cursor != null && cursor.hasMore()) {
            rows = cursor.fetch(); // the chunk read before is released
            rowIndex = 0;
        }
        return rowIndex < rows.size();
    }

    // --- scrolling (TYPE_SCROLL_INSENSITIVE) ---

    private void ensureScrollable() throws SQLException {
        ensureOpen();
        if (!scrollable) {
            throw new SQLException("nusadb: the result set is TYPE_FORWARD_ONLY; "
                    + "create the statement with ResultSet.TYPE_SCROLL_INSENSITIVE to move back", "24000");
        }
    }

    /** Moves to the 0-based {@code index}, clamped to just before the first or after the last row. */
    private boolean moveTo(int index) {
        leaveInsertRow();
        rowIndex = Math.max(-1, Math.min(index, rows.size()));
        return rowIndex >= 0 && rowIndex < rows.size();
    }

    @Override
    public boolean previous() throws SQLException {
        ensureScrollable();
        return moveTo(rowIndex - 1);
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        ensureScrollable();
        if (row == 0) {
            return moveTo(-1);
        }
        return moveTo(row > 0 ? row - 1 : rows.size() + row);
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        ensureScrollable();
        return moveTo(rowIndex + rows);
    }

    @Override
    public boolean first() throws SQLException {
        ensureScrollable();
        return moveTo(0);
    }

    @Override
    public boolean last() throws SQLException {
        ensureScrollable();
        return moveTo(rows.size() - 1);
    }

    @Override
    public void beforeFirst() throws SQLException {
        ensureScrollable();
        moveTo(-1);
    }

    @Override
    public void afterLast() throws SQLException {
        ensureScrollable();
        moveTo(rows.size());
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        ensureOpen();
        return rowIndex < 0 && (!rows.isEmpty() || cursor != null && cursor.hasMore());
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        ensureOpen();
        return !rows.isEmpty() && rowIndex >= rows.size() && (cursor == null || !cursor.hasMore());
    }

    @Override
    public boolean isFirst() throws SQLException {
        ensureOpen();
        return rowIndex == 0 && !rows.isEmpty() && (cursor == null || !cursor.isPastFirstChunk());
    }

    @Override
    public boolean isLast() throws SQLException {
        ensureOpen();
        return rowIndex >= 0 && rowIndex == rows.size() - 1 && (cursor == null || !cursor.hasMore());
    }

    /** The 1-based number of the current row; 0 when there is none. Counted over all chunks of a cursor. */
    @Override
    public int getRow() throws SQLException {
        ensureOpen();
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return 0;
        }
        return (cursor == null ? 0 : (int) cursor.rowsBefore()) + rowIndex + 1;
    }

    @Override
    public int getType() {
        return scrollable ? TYPE_SCROLL_INSENSITIVE : TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() {
        return updater != null ? CONCUR_UPDATABLE : CONCUR_READ_ONLY;
    }

    // --- updating (CONCUR_UPDATABLE) ---

    private void leaveInsertRow() {
        if (onInsertRow) {
            onInsertRow = false;
            changes.clear();
        }
    }

    private RowUpdater updater() throws SQLException {
        ensureOpen();
        if (updater == null) {
            throw new SQLException("nusadb: the result set is CONCUR_READ_ONLY; create the statement with"
                    + " ResultSet.CONCUR_UPDATABLE to change rows through it", "24000");
        }
        return updater;
    }

    private void ensureCurrentRow() throws SQLException {
        if (onInsertRow) {
            throw new SQLException("nusadb: not allowed on the insert row", "24000");
        }
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new SQLException("nusadb: no current row", "24000");
        }
    }

    @Override
    void update(int columnIndex, Object value) throws SQLException {
        RowUpdater u = updater();
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("nusadb: column index out of range: " + columnIndex, "07009");
        }
        if (!onInsertRow) {
            ensureCurrentRow();
        }
        u.checkColumn(columns, columnIndex - 1);
        changes.put(columnIndex - 1, value);
    }

    @Override
    public void updateRow() throws SQLException {
        RowUpdater u = updater();
        ensureCurrentRow();
        if (changes.isEmpty()) {
            return;
        }
        rows.set(rowIndex, u.update(columns, rows.get(rowIndex), changes));
        changes.clear();
    }

    @Override
    public void insertRow() throws SQLException {
        RowUpdater u = updater();
        if (!onInsertRow) {
            throw new SQLException("nusadb: insertRow needs moveToInsertRow first", "24000");
        }
        rows.add(u.insert(columns, changes));
        changes.clear();
    }

    @Override
    public void deleteRow() throws SQLException {
        RowUpdater u = updater();
        ensureCurrentRow();
        u.delete(columns, rows.get(rowIndex));
        rows.remove(rowIndex);
        rowIndex--;
        changes.clear();
    }

    @Override
    public void refreshRow() throws SQLException {
        RowUpdater u = updater();
        ensureCurrentRow();
        rows.set(rowIndex, u.refresh(columns, rows.get(rowIndex)));
        changes.clear();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        updater();
        if (onInsertRow) {
            throw new SQLException("nusadb: cancelRowUpdates is not allowed on the insert row", "24000");
        }
        changes.clear();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        updater().check(columns);
        changes.clear();
        onInsertRow = true;
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        updater();
        leaveInsertRow();
    }

    /** Changes are not marked on the row: re-reading it shows them instead (ownUpdatesAreVisible). */
    @Override
    public boolean rowUpdated() throws SQLException {
        ensureOpen();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        ensureOpen();
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        ensureOpen();
        return false;
    }

    @Override
    public int getFetchDirection() {
        return fetchDirection;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        ensureOpen();
        if (direction == FETCH_FORWARD || direction == FETCH_UNKNOWN || direction == FETCH_REVERSE && scrollable) {
            fetchDirection = direction;
            return;
        }
        throw new SQLException("nusadb: fetch direction " + direction + " needs a scrollable result set", "24000");
    }

    @Override
    public void close() {
        closed = true;
        if (cursor != null) {
            cursor.close();
        }
    }

    @Override
    public int getFetchSize() {
        return cursor != null ? cursor.getFetchSize() : 0;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        if (rows < 0) {
            throw new SQLException("nusadb: fetch size must be >= 0", "22023");
        }
        if (cursor != null) {
            cursor.setFetchSize(rows);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean wasNull() {
        return lastWasNull;
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return new NusaResultSetMetaData(columns, columnTypes);
    }

    @Override
    public Statement getStatement() {
        return statement;
    }

    // --- by-index getters ---

    @Override
    public String getString(int columnIndex) throws SQLException {
        return text(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return false;
        }
        return s.equalsIgnoreCase("true") || s.equals("t") || s.equals("1");
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) ranged(columnIndex, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) ranged(columnIndex, Short.MIN_VALUE, Short.MAX_VALUE, "short");
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return (int) ranged(columnIndex, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    /** getLong, refusing a value the narrower Java type cannot hold instead of wrapping it. */
    private long ranged(int columnIndex, long min, long max, String type) throws SQLException {
        long v = getLong(columnIndex);
        if (v < min || v > max) {
            throw new SQLException("nusadb: column " + columnIndex + " value " + v + " does not fit in " + type,
                    "22003");
        }
        return v;
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            // A NUMERIC column prints its scale ("1000.0000"); that is still a whole number. A value
            // with a real fraction, or out of range, is an error rather than silently truncated.
            try {
                return new BigDecimal(s.trim()).longValueExact();
            } catch (NumberFormatException | ArithmeticException e2) {
                throw new SQLException("nusadb: cannot read column " + columnIndex + " as long: " + s, "22018", e);
            }
        }
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as double: " + s, "22018", e);
        }
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as decimal: " + s, "22018", e);
        }
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        byte[] b = cell(columnIndex);
        if (b == null) {
            return null;
        }
        // A BYTEA column arrives in text format as `\x<hex>` (protocol 1.1 type tag BYTES); decode it
        // to the raw bytes. Any other column returns its field bytes verbatim.
        if (isBytea(columnIndex)) {
            return decodeHex(new String(b, StandardCharsets.UTF_8));
        }
        return b.clone();
    }

    /** Whether result column {@code columnIndex} is a BYTEA column (typed RowDescription, 1.1). */
    private boolean isBytea(int columnIndex) {
        int i = columnIndex - 1;
        return i >= 0 && i < columnTypes.size() && "BYTES".equals(columnTypes.get(i));
    }

    /** Decode the BYTEA hex text form {@code \x<hex>} to raw bytes (empty {@code \x} → empty array). */
    private static byte[] decodeHex(String s) throws SQLException {
        String h = s.startsWith("\\x") ? s.substring(2) : s;
        if ((h.length() & 1) != 0) {
            throw new SQLException("nusadb: malformed BYTEA hex (odd length)", "22P03");
        }
        byte[] out = new byte[h.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            throw new SQLException("nusadb: malformed BYTEA hex", "22P03", e);
        }
        return out;
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return getDate(columnIndex, null);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            // A TIMESTAMP column read as a date keeps only its date part.
            String d = s.trim();
            return Temporals.date(d.length() > 10 ? d.substring(0, 10) : d, cal);
        } catch (RuntimeException e) {
            throw Temporals.unreadable(columnIndex, "date", s, e);
        }
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return getTime(columnIndex, null);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            // A TIMETZ maps to the instant its offset names; a plain TIME is wall-clock time in
            // cal's zone (the JVM default without one). java.sql.Time keeps milliseconds only.
            return Temporals.time(s, cal);
        } catch (RuntimeException e) {
            throw Temporals.unreadable(columnIndex, "time", s, e);
        }
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return getTimestamp(columnIndex, null);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        String s = text(columnIndex);
        if (s == null) {
            return null;
        }
        try {
            // A TIMESTAMPTZ maps to the instant its offset names; a plain TIMESTAMP is wall-clock
            // time in cal's zone (the JVM default without one).
            return Temporals.timestamp(s, cal);
        } catch (RuntimeException e) {
            throw Temporals.unreadable(columnIndex, "timestamp", s, e);
        }
    }

    // A CLOB or BLOB column is a text or bytea value read in full, so these wrap the value.

    @Override
    public java.sql.Clob getClob(int columnIndex) throws SQLException {
        return getNClob(columnIndex);
    }

    @Override
    public java.sql.NClob getNClob(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        return s == null ? null : new NusaClob(s);
    }

    @Override
    public java.sql.Blob getBlob(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b == null ? null : new NusaBlob(b);
    }

    /** An array column, read from its text form {@code {a,b}}; the elements are strings. */
    @Override
    public java.sql.Array getArray(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        return s == null ? null : new NusaArray("text", NusaArray.parse(s));
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        return s == null ? null : new StringReader(s);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String s = text(columnIndex);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    /** The column's protocol 1.1 type name (e.g. {@code "INT"}), or {@code null} if not reported. */
    private String columnType(int columnIndex) {
        int i = columnIndex - 1;
        return i >= 0 && i < columnTypes.size() ? columnTypes.get(i) : null;
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        // Return the Java type that matches the column's SQL type (JDBC fidelity), reusing the typed
        // getters above. BYTEA is handled first since it decodes the field rather than reading text.
        String type = columnType(columnIndex);
        if ("BYTES".equals(type)) {
            return getBytes(columnIndex);
        }
        if ("ARRAY".equals(type)) {
            return getArray(columnIndex);
        }
        String s = text(columnIndex); // also sets wasNull()
        if (s == null) {
            return null;
        }
        if (type == null) {
            return s;
        }
        switch (type) {
            case "BOOL":
                return getBoolean(columnIndex);
            case "INT":
                return getLong(columnIndex);
            case "FLOAT":
                return getDouble(columnIndex);
            case "NUMERIC":
                return getBigDecimal(columnIndex);
            case "DATE":
                return getDate(columnIndex);
            case "TIME":
            case "TIMETZ":
                return getTime(columnIndex);
            case "TIMESTAMP":
            case "TIMESTAMPTZ":
                return getTimestamp(columnIndex);
            case "UUID":
                try {
                    return UUID.fromString(s);
                } catch (IllegalArgumentException e) {
                    return s;
                }
            default:
                // TEXT / JSON / INTERVAL / ARRAY / VECTOR / unknown have no distinct Java type here.
                return s;
        }
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        if (type == null) {
            throw new SQLException("nusadb: getObject type must not be null", "HY009");
        }
        Object v = convert(columnIndex, type);
        return lastWasNull ? null : type.cast(v);
    }

    /** JDBC 4.2 {@code getObject(int, Class)}: the column read as {@code type}. */
    private Object convert(int columnIndex, Class<?> type) throws SQLException {
        if (type == String.class) {
            return getString(columnIndex);
        }
        if (type == Object.class) {
            return getObject(columnIndex);
        }
        if (type == byte[].class) {
            return getBytes(columnIndex);
        }
        if (type == java.sql.Clob.class || type == java.sql.NClob.class) {
            return getNClob(columnIndex);
        }
        if (type == java.sql.Blob.class) {
            return getBlob(columnIndex);
        }
        if (type == java.sql.Array.class) {
            return getArray(columnIndex);
        }
        String s = text(columnIndex); // sets wasNull() for every branch below
        if (s == null) {
            return null;
        }
        try {
            if (type == Long.class) {
                return getLong(columnIndex);
            }
            if (type == Integer.class) {
                return getInt(columnIndex);
            }
            if (type == Short.class) {
                return getShort(columnIndex);
            }
            if (type == Byte.class) {
                return getByte(columnIndex);
            }
            if (type == Boolean.class) {
                return getBoolean(columnIndex);
            }
            if (type == Double.class) {
                return getDouble(columnIndex);
            }
            if (type == Float.class) {
                return getFloat(columnIndex);
            }
            if (type == BigDecimal.class) {
                return getBigDecimal(columnIndex);
            }
            if (type == java.math.BigInteger.class) {
                return getBigDecimal(columnIndex).toBigIntegerExact();
            }
            if (type == UUID.class) {
                return UUID.fromString(s.trim());
            }
            if (type == Date.class) {
                return getDate(columnIndex);
            }
            if (type == Time.class) {
                return getTime(columnIndex);
            }
            if (type == Timestamp.class) {
                return getTimestamp(columnIndex);
            }
            if (type == LocalDate.class) {
                String d = s.trim();
                return Temporals.localDate(d.length() > 10 ? d.substring(0, 10) : d);
            }
            if (type == LocalTime.class) {
                return Temporals.localTime(s);
            }
            if (type == OffsetTime.class) {
                return Temporals.offsetTime(s);
            }
            if (type == LocalDateTime.class) {
                return Temporals.localDateTime(s);
            }
            if (type == OffsetDateTime.class) {
                // A plain TIMESTAMP has no offset of its own; read it as UTC.
                return Temporals.offsetDateTime(s, ZoneOffset.UTC);
            }
            if (type == ZonedDateTime.class) {
                return Temporals.offsetDateTime(s, ZoneOffset.UTC).toZonedDateTime();
            }
            if (type == Instant.class) {
                return Temporals.offsetDateTime(s, ZoneOffset.UTC).toInstant();
            }
            if (type == java.util.Date.class) {
                return getTimestamp(columnIndex);
            }
        } catch (ArithmeticException | IllegalArgumentException | java.time.DateTimeException e) {
            throw new SQLException("nusadb: cannot read column " + columnIndex + " as "
                    + type.getSimpleName() + ": " + s, "22018", e);
        }
        throw new SQLFeatureNotSupportedException(
                "nusadb: getObject cannot convert to " + type.getName());
    }

    // --- by-label getters delegate to findColumn ---

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public java.sql.Clob getClob(String columnLabel) throws SQLException {
        return getClob(findColumn(columnLabel));
    }

    @Override
    public java.sql.NClob getNClob(String columnLabel) throws SQLException {
        return getNClob(findColumn(columnLabel));
    }

    @Override
    public java.sql.Blob getBlob(String columnLabel) throws SQLException {
        return getBlob(findColumn(columnLabel));
    }

    @Override
    public java.sql.Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getNString(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getNCharacterStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel), cal);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel), cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel), cal);
    }
}
