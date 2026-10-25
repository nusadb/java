package com.nusadb.jdbc;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The JDBC escape syntax, translated to the SQL the server runs before a statement is sent:
 * <ul>
 *   <li>{@code {d 'yyyy-mm-dd'}}, {@code {t 'hh:mm:ss'}}, {@code {ts '...'}}: typed literals;</li>
 *   <li>{@code {fn name(args)}}: the scalar functions of the JDBC specification (appendix C);</li>
 *   <li>{@code {oj ...}}: an outer join, which needs no translation;</li>
 *   <li>{@code {escape 'c'}}: the LIKE escape character;</li>
 *   <li>{@code {limit n offset m}}.</li>
 * </ul>
 * String literals, quoted identifiers and comments are copied as written. A translation never
 * repeats an argument, so the order of {@code ?} parameters is kept. {@code {call ..}} is left
 * as written ({@code CallableStatement} is not supported).
 */
final class JdbcEscapes {

    private JdbcEscapes() {}

    /** The SQL with every escape translated; the same string when it has none. */
    static String translate(String sql) throws SQLException {
        if (sql == null || sql.indexOf('{') < 0) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int end = copy(sql, 0, out, false);
        if (end != sql.length()) {
            throw syntax(sql, "unbalanced '}'");
        }
        return out.toString();
    }

    /**
     * Copies {@code sql} from {@code from} into {@code out}, translating escapes, until the end or
     * (when {@code nested}) the '}' that closes the escape being read. Returns the index reached.
     */
    private static int copy(String sql, int from, StringBuilder out, boolean nested) throws SQLException {
        int len = sql.length();
        int i = from;
        while (i < len) {
            char ch = sql.charAt(i);
            if (ch == '\'' || ch == '"') {
                i = copyQuoted(sql, i, out);
            } else if (ch == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int eol = sql.indexOf('\n', i);
                int stop = eol < 0 ? len : eol + 1;
                out.append(sql, i, stop);
                i = stop;
            } else if (ch == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                int stop = close < 0 ? len : close + 2;
                out.append(sql, i, stop);
                i = stop;
            } else if (ch == '{') {
                i = escape(sql, i, out);
            } else if (ch == '}' && nested) {
                return i;
            } else {
                out.append(ch);
                i++;
            }
        }
        if (nested) {
            throw syntax(sql, "an escape is not closed with '}'");
        }
        return i;
    }

    /** Copies a quoted literal or identifier starting at {@code i}; returns the index after it. */
    private static int copyQuoted(String sql, int i, StringBuilder out) {
        char quote = sql.charAt(i);
        int len = sql.length();
        out.append(quote);
        for (i++; i < len; i++) {
            char c = sql.charAt(i);
            out.append(c);
            if (c == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    out.append(quote);
                    i++;
                } else {
                    return i + 1;
                }
            }
        }
        return len;
    }

    /** Translates the escape whose '{' is at {@code open}; returns the index after its '}'. */
    private static int escape(String sql, int open, StringBuilder out) throws SQLException {
        int i = skipSpaces(sql, open + 1);
        int start = i;
        while (i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '?' || sql.charAt(i) == '=')) {
            i++;
        }
        String keyword = sql.substring(start, i).toLowerCase(Locale.ROOT);
        StringBuilder body = new StringBuilder();
        int close = copy(sql, i, body, true);
        String inner = body.toString().trim();
        switch (keyword) {
            case "d":
                out.append("DATE ").append(inner);
                break;
            case "t":
                out.append("TIME ").append(inner);
                break;
            case "ts":
                out.append("TIMESTAMP ").append(inner);
                break;
            case "escape":
                out.append("ESCAPE ").append(inner);
                break;
            case "oj":
                out.append(inner);
                break;
            case "limit":
                out.append("LIMIT ").append(inner.replaceFirst("(?i)\\boffset\\b", "OFFSET"));
                break;
            case "fn":
                out.append(function(sql, inner));
                break;
            default:
                // {call ..}, {?= call ..} and anything unknown stay as written.
                out.append(sql, open, close + 1);
                return close + 1;
        }
        return close + 1;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** {@code name(args)}, with its arguments already translated, as the server's SQL. */
    private static String function(String sql, String call) throws SQLException {
        int paren = call.indexOf('(');
        if (paren < 0 || !call.endsWith(")")) {
            throw syntax(sql, "{fn " + call + "} is not a function call");
        }
        String name = call.substring(0, paren).trim().toLowerCase(Locale.ROOT);
        List<String> args = arguments(call.substring(paren + 1, call.length() - 1));
        int n = args.size();
        switch (name) {
            // numeric
            case "ceiling": return "ceil(" + args(args, 1, sql, name) + ")";
            case "log": return "ln(" + args(args, 1, sql, name) + ")";
            case "rand": return "random()";
            case "truncate": return "trunc(" + args(args, 2, sql, name) + ")";
            // string
            case "char": return "chr(" + args(args, 1, sql, name) + ")";
            case "concat": return "(" + arg(args, 0, 2, sql, name) + " || " + args.get(1) + ")";
            case "insert": {
                arg(args, 0, 4, sql, name);
                return "overlay(" + args.get(0) + " placing " + args.get(3) + " from " + args.get(1) + " for " + args.get(2) + ")";
            }
            case "lcase": return "lower(" + args(args, 1, sql, name) + ")";
            case "ucase": return "upper(" + args(args, 1, sql, name) + ")";
            case "length": return "length(rtrim(" + args(args, 1, sql, name) + "))";
            case "locate": {
                if (n != 2) {
                    throw new SQLFeatureNotSupportedException("nusadb: {fn locate} with a start position is not supported", "0A000");
                }
                return "position(" + args.get(0) + " in " + args.get(1) + ")";
            }
            case "space": return "repeat(' ', " + args(args, 1, sql, name) + ")";
            case "substring": return "substr(" + String.join(", ", args) + ")";
            // date and time
            case "curdate":
            case "current_date": return "current_date";
            case "curtime":
            case "current_time": return "current_time";
            case "now":
            case "current_timestamp": return "now()";
            case "dayname": return "to_char(" + args(args, 1, sql, name) + ", 'Day')";
            case "monthname": return "to_char(" + args(args, 1, sql, name) + ", 'Month')";
            case "dayofmonth": return extract("day", args, sql, name);
            case "dayofweek": return "(" + extract("dow", args, sql, name) + " + 1)";
            case "dayofyear": return extract("doy", args, sql, name);
            case "year":
            case "month":
            case "week":
            case "quarter":
            case "hour":
            case "minute": return extract(name, args, sql, name);
            case "second": return "trunc(" + extract("second", args, sql, name) + ")";
            case "timestampadd": {
                arg(args, 0, 3, sql, name);
                return "(" + args.get(2) + " + (" + args.get(1) + ") * interval '" + interval(sql, args.get(0)) + "')";
            }
            case "timestampdiff": {
                arg(args, 0, 3, sql, name);
                return timestampdiff(sql, args.get(0), args.get(1), args.get(2));
            }
            // system
            case "database": return "current_database()";
            case "user": return "current_user";
            case "ifnull": return "coalesce(" + args(args, 2, sql, name) + ")";
            default:
                // abs, acos, ascii, asin, atan, atan2, cos, cot, degrees, exp, floor, left, log10,
                // ltrim, mod, pi, power, radians, repeat, replace, right, round, rtrim, sign, sin,
                // sqrt, tan: the server has them under the same name.
                return name + "(" + String.join(", ", args) + ")";
        }
    }

    private static String extract(String field, List<String> args, String sql, String name) throws SQLException {
        return "extract(" + field + " from " + args(args, 1, sql, name) + ")";
    }

    /** The interval for a {@code SQL_TSI_*} unit of {@code timestampadd}. */
    private static String interval(String sql, String unit) throws SQLException {
        switch (unit.trim().toUpperCase(Locale.ROOT)) {
            case "SQL_TSI_FRAC_SECOND": return "1 microsecond";
            case "SQL_TSI_SECOND": return "1 second";
            case "SQL_TSI_MINUTE": return "1 minute";
            case "SQL_TSI_HOUR": return "1 hour";
            case "SQL_TSI_DAY": return "1 day";
            case "SQL_TSI_WEEK": return "1 week";
            case "SQL_TSI_MONTH": return "1 month";
            case "SQL_TSI_QUARTER": return "3 month";
            case "SQL_TSI_YEAR": return "1 year";
            default: throw syntax(sql, "unknown interval " + unit);
        }
    }

    /** {@code timestampdiff(unit, from, to)} for the units measured in elapsed time. */
    private static String timestampdiff(String sql, String unit, String from, String to) throws SQLException {
        String seconds = "extract(epoch from (" + to + " - " + from + "))";
        switch (unit.trim().toUpperCase(Locale.ROOT)) {
            case "SQL_TSI_SECOND": return "trunc(" + seconds + ")";
            case "SQL_TSI_MINUTE": return "trunc(" + seconds + " / 60)";
            case "SQL_TSI_HOUR": return "trunc(" + seconds + " / 3600)";
            case "SQL_TSI_DAY": return "trunc(" + seconds + " / 86400)";
            case "SQL_TSI_WEEK": return "trunc(" + seconds + " / 604800)";
            default:
                throw new SQLFeatureNotSupportedException("nusadb: {fn timestampdiff} by " + unit + " is not supported", "0A000");
        }
    }

    private static String args(List<String> args, int count, String sql, String name) throws SQLException {
        arg(args, 0, count, sql, name);
        return String.join(", ", args);
    }

    private static String arg(List<String> args, int index, int count, String sql, String name) throws SQLException {
        if (args.size() != count) {
            throw syntax(sql, "{fn " + name + "} takes " + count + " argument(s), got " + args.size());
        }
        return args.get(index);
    }

    /** The comma-separated arguments of a call, split at the top level only. */
    private static List<String> arguments(String list) {
        List<String> out = new ArrayList<>();
        if (list.trim().isEmpty()) {
            return out;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '\'' || c == '"') {
                i = copyQuoted(list, i, new StringBuilder()) - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(list.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(list.substring(start).trim());
        return out;
    }

    private static SQLException syntax(String sql, String problem) {
        return new SQLException("nusadb: JDBC escape syntax: " + problem + " in: " + sql, "42601");
    }
}
