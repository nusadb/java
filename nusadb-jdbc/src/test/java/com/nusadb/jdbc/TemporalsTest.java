package com.nusadb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** Parsing the server's date/time text and rendering parameters; no server needed. */
class TemporalsTest {

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    @Test
    void readsTimestamptzWithHourOnlyOffset() {
        // The exact text the server sends for a TIMESTAMPTZ column.
        Timestamp ts = Temporals.timestamp("2026-01-02 03:11:12+00", null);
        assertEquals(Instant.parse("2026-01-02T03:11:12Z"), ts.toInstant());
    }

    @Test
    void readsOffsetsWithMinutesSecondsAndFractions() {
        assertEquals(OffsetDateTime.parse("2026-01-02T03:11:12.123456+05:30"),
                Temporals.offsetDateTime("2026-01-02 03:11:12.123456+05:30", ZoneOffset.UTC));
        assertEquals(OffsetDateTime.parse("2026-01-02T03:11:12-03:30"),
                Temporals.offsetDateTime("2026-01-02 03:11:12-03:30:00", ZoneOffset.UTC));
        assertEquals(OffsetDateTime.parse("2026-01-02T03:11:12Z"),
                Temporals.offsetDateTime("2026-01-02T03:11:12Z", ZoneOffset.UTC));
    }

    @Test
    void plainTimestampIsWallClockInTheCalendarZone() {
        Timestamp viaUtc = Temporals.timestamp("2026-01-02 03:11:12", utc());
        assertEquals(Instant.parse("2026-01-02T03:11:12Z"), viaUtc.toInstant());
        Timestamp noCal = Temporals.timestamp("2026-01-02 03:11:12.5", null);
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 11, 12, 500_000_000), noCal.toLocalDateTime());
    }

    @Test
    void dateTextIsNotMistakenForAnOffset() {
        assertFalse(Temporals.hasOffset("2026-01-02"));
        assertFalse(Temporals.hasOffset("2026-01-02 10:11:12"));
        assertTrue(Temporals.hasOffset("2026-01-02 10:11:12+07"));
        assertEquals(LocalDate.of(2026, 1, 2), Temporals.localDate("2026-01-02"));
    }

    @Test
    void timeAndTimetz() {
        assertEquals(LocalTime.of(10, 11, 12), Temporals.localTime("10:11:12+07"));
        assertEquals(OffsetTime.parse("10:11:12+07:00"), Temporals.offsetTime("10:11:12+07"));
        assertEquals(LocalTime.of(10, 11, 12, 123_456_000), Temporals.localTime("10:11:12.123456"));
    }

    @Test
    void parametersUseTheServersTextForm() {
        assertEquals("2026-01-02 03:04:05", Temporals.parameter(LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
        assertEquals("2026-01-02 03:04:05+07:00",
                Temporals.parameter(OffsetDateTime.parse("2026-01-02T03:04:05+07:00")));
        assertEquals("2026-01-02 03:04:05+00:00", Temporals.parameter(Instant.parse("2026-01-02T03:04:05Z")));
        // A ZonedDateTime's "[Asia/Jakarta]" suffix would not parse server-side.
        assertEquals("2026-01-02 03:04:05+07:00", Temporals.parameter(
                ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneId.of("Asia/Jakarta"))));
        assertEquals("2026-01-02", Temporals.parameter(LocalDate.of(2026, 1, 2)));
        assertNull(Temporals.parameter("not a date"));
    }

    @Test
    void timestampWithCalendarIsWallClockInThatZone() {
        Timestamp ts = Timestamp.from(Instant.parse("2026-01-02T03:04:05.250Z"));
        assertEquals("2026-01-02 03:04:05.25", Temporals.format(ts, utc()));
        Calendar plus7 = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:00"));
        assertEquals("2026-01-02 10:04:05.25", Temporals.format(ts, plus7));
        assertEquals(ts.toLocalDateTime(), LocalDateTime.parse(
                Temporals.format(ts, null).replace(' ', 'T')));
    }
}
