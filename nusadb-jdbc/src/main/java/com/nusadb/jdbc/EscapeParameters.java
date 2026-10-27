package com.nusadb.jdbc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The server takes only a literal after {@code LIKE .. ESCAPE}, while some frameworks bind the escape
 * character as a parameter ({@code like ? escape ?}). For such a statement the value bound to each
 * {@code ESCAPE $n} is written into the SQL as a quoted literal when it runs; a parameter used only
 * there is then left out and the later ones numbered down.
 */
final class EscapeParameters {
    /** The SQL as prepared ({@code $n} placeholders). */
    private final String sql;
    /** Start and end offset of each {@code $n} after ESCAPE, and its number. */
    private final List<int[]> escapes;
    /** Parameter numbers used only after ESCAPE: left out of the rewritten statement. */
    private final boolean[] dropped;
    /** Start, end and number of every other {@code $n}. */
    private final List<int[]> others;

    private EscapeParameters(String sql, List<int[]> escapes, List<int[]> others, boolean[] dropped) {
        this.sql = sql;
        this.escapes = escapes;
        this.others = others;
        this.dropped = dropped;
    }

    /** The escape parameters of {@code sql}, or null when it has none. */
    static EscapeParameters of(String sql) {
        if (sql.indexOf('$') < 0 || !sql.toLowerCase(Locale.ROOT).contains("escape")) {
            return null;
        }
        boolean[] code = code(sql);
        List<int[]> escapes = new ArrayList<>();
        List<int[]> others = new ArrayList<>();
        int max = 0;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            if (!code[i] || sql.charAt(i) != '$' || i + 1 >= n || !Character.isDigit(sql.charAt(i + 1))
                    || i > 0 && (Character.isLetterOrDigit(sql.charAt(i - 1)) || sql.charAt(i - 1) == '_')) {
                continue;
            }
            int j = i + 1;
            while (j < n && Character.isDigit(sql.charAt(j))) {
                j++;
            }
            int number = Integer.parseInt(sql.substring(i + 1, j));
            max = Math.max(max, number);
            (afterEscape(sql, code, i) ? escapes : others).add(new int[] {i, j, number});
            i = j - 1;
        }
        if (escapes.isEmpty()) {
            return null;
        }
        boolean[] dropped = new boolean[max + 1];
        for (int[] e : escapes) {
            dropped[e[2]] = true;
        }
        for (int[] o : others) {
            dropped[o[2]] = false;
        }
        return new EscapeParameters(sql, escapes, others, dropped);
    }

    /** Whether the code just before {@code at} is the word ESCAPE. */
    private static boolean afterEscape(String sql, boolean[] code, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(sql.charAt(i))) {
            i--;
        }
        int end = i + 1;
        while (i >= 0 && code[i] && Character.isLetter(sql.charAt(i))) {
            i--;
        }
        boolean wordStart = i < 0 || !(Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_');
        return wordStart && end - (i + 1) == 6 && sql.substring(i + 1, end).equalsIgnoreCase("escape");
    }

    /** For each character, whether it is SQL code rather than inside a literal, quoted name or comment. */
    private static boolean[] code(String sql) {
        int n = sql.length();
        boolean[] code = new boolean[n];
        for (int i = 0; i < n; ) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                i = j + 1;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                code[i++] = true;
            }
        }
        return code;
    }

    /** A parameter's new number: one less for each dropped parameter before it. */
    private int renumber(int number) {
        int shift = 0;
        for (int k = 1; k < number && k < dropped.length; k++) {
            if (dropped[k]) {
                shift++;
            }
        }
        return number - shift;
    }

    /** The statement with the escape values of {@code values} written in. */
    String sql(List<byte[]> values) {
        List<int[]> all = new ArrayList<>(escapes.size() + others.size());
        all.addAll(escapes);
        all.addAll(others);
        all.sort((a, b) -> Integer.compare(a[0], b[0]));
        StringBuilder out = new StringBuilder(sql.length() + 8);
        int last = 0;
        for (int[] p : all) {
            out.append(sql, last, p[0]);
            if (escapes.contains(p)) {
                byte[] v = p[2] - 1 < values.size() ? values.get(p[2] - 1) : null;
                out.append(v == null ? "NULL" : "'" + new String(v, StandardCharsets.UTF_8).replace("'", "''") + "'");
            } else {
                out.append('$').append(renumber(p[2]));
            }
            last = p[1];
        }
        return out.append(sql, last, sql.length()).toString();
    }

    /** {@code values} without the dropped parameters. */
    List<byte[]> values(List<byte[]> values) {
        List<byte[]> out = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            if (i + 1 >= dropped.length || !dropped[i + 1]) {
                out.add(values.get(i));
            }
        }
        return out;
    }

    /** {@code tags} without the dropped parameters. */
    byte[] tags(byte[] tags) {
        byte[] out = new byte[tags.length];
        int n = 0;
        for (int i = 0; i < tags.length; i++) {
            if (i + 1 >= dropped.length || !dropped[i + 1]) {
                out[n++] = tags[i];
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
