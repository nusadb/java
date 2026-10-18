package com.nusadb.jdbc;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Text conversions between the server's date/time output and Java's date/time types.
 *
 * <p>The server prints {@code DATE} as {@code 2026-01-02}, {@code TIME} as {@code 10:11:12[.ffffff]},
 * {@code TIMESTAMP} as {@code 2026-01-02 10:11:12[.ffffff]}, and the zoned variants with a trailing
 * offset such as {@code +00}, {@code +05:30} or {@code -03:30:00}. Parameters are sent as ISO text,
 * which the server accepts for every one of these column types.
 */
final class Temporals {

    private Temporals() {}

    /** {@code HH:mm:ss} plus up to nine fractional digits. */
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    /** {@code yyyy-MM-dd HH:mm:ss[.f]}, also accepting a {@code T} separator. */
    private static final DateTimeFormatter LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .append(TIME)
            .toFormatter();

    /** Split a zoned value into its local part and its offset ({@code null} when there is none). */
    private static String[] splitOffset(String s) {
        int i = s.length() - 1;
        // Walk back over the offset's digits and colons to the sign that starts it.
        while (i > 0 && (Character.isDigit(s.charAt(i)) || s.charAt(i) == ':')) {
            i--;
        }
        char c = s.charAt(i);
        // A date's own '-' sits at index 4 or 7; an offset sign always follows the time part.
        if ((c == '+' || c == '-') && s.indexOf(':') >= 0 && s.indexOf(':') < i) {
            return new String[] {s.substring(0, i), s.substring(i)};
        }
        if (c == 'Z' || c == 'z') {
            return new String[] {s.substring(0, i), "Z"};
        }
        return new String[] {s, null};
    }

    private static ZoneOffset offset(String text) {
        if ("Z".equals(text)) {
            return ZoneOffset.UTC;
        }
        String t = text;
        if (t.length() == 3) {
            t = t + ":00"; // "+05" -> "+05:00"
        }
        return ZoneOffset.of(t);
    }

    static LocalDate localDate(String s) {
        return LocalDate.parse(s.trim());
    }

    static LocalTime localTime(String s) {
        return LocalTime.parse(splitOffset(s.trim())[0], TIME);
    }

    /** A TIME or TIMETZ value; a value without an offset is read in UTC. */
    static OffsetTime offsetTime(String s) {
        String[] p = splitOffset(s.trim());
        LocalTime t = LocalTime.parse(p[0], TIME);
        return t.atOffset(p[1] == null ? ZoneOffset.UTC : offset(p[1]));
    }

    /** The wall-clock part of a TIMESTAMP or TIMESTAMPTZ (the offset, if any, is dropped). */
    static LocalDateTime localDateTime(String s) {
        return LocalDateTime.parse(splitOffset(s.trim())[0], LOCAL_DATE_TIME);
    }

    /**
     * A TIMESTAMP or TIMESTAMPTZ as an instant on the time line. A value with no offset (plain
     * TIMESTAMP) is placed in {@code zone}.
     */
    static OffsetDateTime offsetDateTime(String s, ZoneId zone) {
        String[] p = splitOffset(s.trim());
        LocalDateTime ldt = LocalDateTime.parse(p[0], LOCAL_DATE_TIME);
        if (p[1] != null) {
            return ldt.atOffset(offset(p[1]));
        }
        return ldt.atZone(zone).toOffsetDateTime();
    }

    static boolean hasOffset(String s) {
        return splitOffset(s.trim())[1] != null;
    }

    // --- java.sql types --------------------------------------------------------------

    private static ZoneId zone(Calendar cal) {
        TimeZone tz = cal == null ? TimeZone.getDefault() : cal.getTimeZone();
        return tz.toZoneId();
    }

    /**
     * A TIMESTAMP is read as wall-clock time in {@code cal}'s zone (the JVM default when null); a
     * TIMESTAMPTZ carries its own offset and maps to that exact instant.
     */
    static Timestamp timestamp(String s, Calendar cal) {
        String[] p = splitOffset(s.trim());
        LocalDateTime ldt = LocalDateTime.parse(p[0], LOCAL_DATE_TIME);
        if (p[1] == null && cal == null) {
            return Timestamp.valueOf(ldt); // keeps nanos, no zone arithmetic
        }
        Instant instant = p[1] != null ? ldt.toInstant(offset(p[1])) : ldt.atZone(zone(cal)).toInstant();
        return Timestamp.from(instant);
    }

    static Time time(String s, Calendar cal) {
        String[] p = splitOffset(s.trim());
        LocalTime t = LocalTime.parse(p[0], TIME);
        if (p[1] == null && cal == null) {
            return Time.valueOf(t);
        }
        // java.sql.Time is the time on 1970-01-01; place it in the value's offset or cal's zone.
        ZonedDateTime z = p[1] != null
                ? t.atDate(LocalDate.EPOCH).atOffset(offset(p[1])).toZonedDateTime()
                : t.atDate(LocalDate.EPOCH).atZone(zone(cal));
        return new Time(z.toInstant().toEpochMilli());
    }

    static Date date(String s, Calendar cal) {
        LocalDate d = LocalDate.parse(s.trim());
        if (cal == null) {
            return Date.valueOf(d);
        }
        return new Date(d.atStartOfDay(zone(cal)).toInstant().toEpochMilli());
    }

    // --- parameters ------------------------------------------------------------------

    private static final DateTimeFormatter PARAM_LOCAL = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .append(TIME)
            .toFormatter();

    private static final DateTimeFormatter PARAM_OFFSET = new DateTimeFormatterBuilder()
            .append(PARAM_LOCAL)
            .appendOffset("+HH:MM", "+00:00")
            .toFormatter();

    static String format(LocalDateTime v) {
        return PARAM_LOCAL.format(v);
    }

    static String format(OffsetDateTime v) {
        return PARAM_OFFSET.format(v);
    }

    /**
     * A timestamp parameter, written as wall-clock time in {@code cal}'s zone and without an offset:
     * a TIMESTAMP column refuses offset text, and a TIMESTAMPTZ column reads offset-less text in the
     * session time zone (UTC unless changed), so with a UTC calendar both store the exact instant.
     */
    static String format(Timestamp ts, Calendar cal) {
        if (cal == null) {
            return format(ts.toLocalDateTime());
        }
        return format(ts.toInstant().atZone(zone(cal)).toLocalDateTime());
    }

    static String format(Time t, Calendar cal) {
        if (cal == null) {
            return t.toString();
        }
        return TIME.format(Instant.ofEpochMilli(t.getTime()).atZone(zone(cal)).toLocalTime());
    }

    static String format(Date d, Calendar cal) {
        if (cal == null) {
            return d.toString();
        }
        return Instant.ofEpochMilli(d.getTime()).atZone(zone(cal)).toLocalDate().toString();
    }

    /**
     * Render a java.time (or legacy {@code java.util}) value as parameter text, or {@code null} when
     * {@code x} is not a date/time type.
     */
    static String parameter(Object x) {
        if (x instanceof LocalDateTime v) {
            return format(v);
        }
        if (x instanceof OffsetDateTime v) {
            return format(v);
        }
        if (x instanceof ZonedDateTime v) {
            return format(v.toOffsetDateTime());
        }
        if (x instanceof Instant v) {
            return format(v.atOffset(ZoneOffset.UTC));
        }
        if (x instanceof LocalDate || x instanceof LocalTime || x instanceof OffsetTime) {
            return x.toString();
        }
        if (x instanceof Timestamp v) {
            return format(v, null);
        }
        if (x instanceof Date || x instanceof Time) {
            return x.toString();
        }
        if (x instanceof java.util.Date v) {
            return format(v.toInstant().atOffset(ZoneOffset.UTC));
        }
        if (x instanceof Calendar v) {
            return format(v.toInstant().atZone(v.getTimeZone().toZoneId()).toOffsetDateTime());
        }
        return null;
    }

    static SQLException unreadable(int columnIndex, String what, String value, RuntimeException cause) {
        return new SQLException("nusadb: cannot read column " + columnIndex + " as " + what + ": " + value,
                "22007", cause);
    }

    static boolean isParseError(RuntimeException e) {
        return e instanceof DateTimeParseException || e instanceof IllegalArgumentException
                || e instanceof java.time.DateTimeException;
    }
}
