package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nusadb.testing.NusaTestServer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Date/time values in both directions, as Hibernate and Spring bind and read them. */
class TemporalIT {

    private static Connection c;

    @BeforeAll
    static void open() throws SQLException {
        c = NusaTestServer.get().connect();
    }

    @AfterAll
    static void close() throws SQLException {
        c.close();
    }

    @BeforeEach
    void table() throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS tz_t");
            st.execute("CREATE TABLE tz_t (id INT PRIMARY KEY, ts TIMESTAMP, tz TIMESTAMPTZ, d DATE, t TIME, "
                    + "n NUMERIC(20,4), u UUID)");
        }
    }

    private void insert(Object ts, Object tz) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO tz_t (id, ts, tz) VALUES (?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setObject(2, ts);
            ps.setObject(3, tz);
            ps.executeUpdate();
        }
    }

    private ResultSet row() throws SQLException {
        ResultSet rs = c.createStatement().executeQuery("SELECT ts, tz, d, t, n, u FROM tz_t");
        rs.next();
        return rs;
    }

    @Test
    void timestamptzReadsBackAsTheSameInstant() throws SQLException {
        OffsetDateTime at = OffsetDateTime.parse("2026-01-02T10:04:05.123456+07:00");
        insert(LocalDateTime.of(2026, 1, 2, 3, 4, 5), at);
        try (ResultSet rs = row()) {
            assertEquals(at.toInstant(), rs.getTimestamp("tz").toInstant());
            assertInstanceOf(Timestamp.class, rs.getObject("tz"));
            assertEquals(at.toInstant(), rs.getObject("tz", OffsetDateTime.class).toInstant());
            assertEquals(at.toInstant(), rs.getObject("tz", Instant.class));
            assertEquals(at.toInstant(), rs.getObject("tz", ZonedDateTime.class).toInstant());
        }
    }

    @Test
    void instantAndZonedDateTimeBind() throws SQLException {
        Instant at = Instant.parse("2026-03-04T05:06:07Z");
        insert(LocalDateTime.of(2026, 1, 2, 3, 4, 5), ZonedDateTime.ofInstant(at, ZoneId.of("Asia/Jakarta")));
        try (ResultSet rs = row()) {
            assertEquals(at, rs.getObject("tz", Instant.class));
        }
        table();
        insert(LocalDateTime.of(2026, 1, 2, 3, 4, 5), at);
        try (ResultSet rs = row()) {
            assertEquals(at, rs.getObject("tz", Instant.class));
        }
    }

    @Test
    void plainTimestampAsLocalDateTime() throws SQLException {
        LocalDateTime local = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 678_000_000);
        insert(local, OffsetDateTime.now());
        try (ResultSet rs = row()) {
            assertEquals(local, rs.getObject("ts", LocalDateTime.class));
            assertEquals(local, rs.getTimestamp("ts").toLocalDateTime());
            assertEquals(LocalDate.of(2026, 1, 2), rs.getObject("ts", LocalDate.class));
        }
    }

    @Test
    void calendarOverloadsTreatTimestampAsUtcWallClock() throws SQLException {
        // What Hibernate does with hibernate.jdbc.time_zone=UTC. The session time zone is UTC, so the
        // offset-less text lands on the same instant in the TIMESTAMPTZ column.
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        Timestamp at = Timestamp.from(Instant.parse("2026-05-06T07:08:09Z"));
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO tz_t (id, ts, tz) VALUES (?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setTimestamp(2, at, utc);
            ps.setTimestamp(3, at, utc);
            ps.executeUpdate();
        }
        try (ResultSet rs = c.createStatement().executeQuery("SELECT ts::text, ts, tz FROM tz_t")) {
            rs.next();
            assertEquals("2026-05-06 07:08:09", rs.getString(1));
            assertEquals(at, rs.getTimestamp(2, utc));
            assertEquals(at, rs.getTimestamp(3, utc));
        }
    }

    @Test
    void dateTimeNumericUuidThroughGetObjectClass() throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO tz_t (id, d, t, n, u) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setObject(2, LocalDate.of(2026, 7, 8), JDBCType.DATE);
            ps.setObject(3, LocalTime.of(9, 10, 11), Types.TIME);
            ps.setObject(4, new BigDecimal("1E+3"), Types.NUMERIC, 2);
            ps.setObject(5, id, JDBCType.OTHER);
            ps.executeUpdate();
        }
        try (ResultSet rs = row()) {
            assertEquals(LocalDate.of(2026, 7, 8), rs.getObject("d", LocalDate.class));
            assertEquals(LocalTime.of(9, 10, 11), rs.getObject("t", LocalTime.class));
            assertEquals(0, new BigDecimal("1000").compareTo(rs.getObject("n", BigDecimal.class)));
            assertEquals(id, rs.getObject("u", UUID.class));
            assertEquals(1000, rs.getObject("n", Integer.class)); // NUMERIC(20,4) text is "1000.0000"
            assertEquals(1000L, rs.getLong("n"));
            assertNull(rs.getObject("ts", LocalDateTime.class));
        }
    }
}
