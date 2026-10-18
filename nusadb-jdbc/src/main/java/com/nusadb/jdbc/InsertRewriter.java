package com.nusadb.jdbc;

import java.util.Locale;

/**
 * Turns a single-row {@code INSERT ... VALUES ($1, $2)} into the same INSERT with many value rows,
 * so a JDBC batch of it costs one round trip per chunk instead of one per row
 * ({@code rewriteBatchedInserts}).
 *
 * <p>Only the plain shape is rewritten: one {@code VALUES} tuple whose placeholders run
 * {@code $1..$k}, and nothing after it that uses a placeholder. Anything else (INSERT ... SELECT,
 * DEFAULT VALUES, a RETURNING clause, multi-row VALUES already) is left alone and executed row by row.
 */
final class InsertRewriter {

    /** The server reads the parameter count as an unsigned 16-bit number; stay well inside it. */
    static final int MAX_PARAMS = 32767;

    private final String head;   // everything up to and including "VALUES "
    private final String tuple;  // "($1, $2)"
    private final String tail;   // what follows the tuple, e.g. " ON CONFLICT DO NOTHING"
    private final int params;    // k: placeholders per row

    private InsertRewriter(String head, String tuple, String tail, int params) {
        this.head = head;
        this.tuple = tuple;
        this.tail = tail;
        this.params = params;
    }

    int paramsPerRow() {
        return params;
    }

    /** How many rows fit in one statement without crossing {@link #MAX_PARAMS}. */
    int rowsPerStatement() {
        return Math.max(1, MAX_PARAMS / params);
    }

    /** The INSERT carrying {@code rows} value tuples, placeholders renumbered row by row. */
    String sql(int rows) {
        StringBuilder sb = new StringBuilder(head.length() + rows * (tuple.length() + 4) + tail.length());
        sb.append(head);
        for (int r = 0; r < rows; r++) {
            if (r > 0) {
                sb.append(", ");
            }
            sb.append(renumber(tuple, r * params));
        }
        sb.append(tail);
        return sb.toString();
    }

    /** A rewriter for {@code sql}, or {@code null} when it is not a plain single-row INSERT. */
    static InsertRewriter of(String sql) {
        String code = blankLiteralsAndComments(sql);
        String lower = code.toLowerCase(Locale.ROOT);
        if (!lower.stripLeading().startsWith("insert")) {
            return null;
        }
        int values = keyword(lower, "values");
        if (values < 0) {
            return null;
        }
        int open = values + "values".length();
        while (open < code.length() && Character.isWhitespace(code.charAt(open))) {
            open++;
        }
        if (open >= code.length() || code.charAt(open) != '(') {
            return null; // DEFAULT VALUES and the like
        }
        int close = matching(code, open);
        if (close < 0) {
            return null;
        }
        String tailCode = code.substring(close + 1);
        String tailTrim = tailCode.strip();
        if (tailTrim.startsWith(",")) {
            return null; // already multi-row
        }
        String tailLower = tailCode.toLowerCase(Locale.ROOT);
        if (tailCode.indexOf('$') >= 0 || keyword(tailLower, "returning") >= 0 || keyword(tailLower, "select") >= 0) {
            return null;
        }
        int k = placeholders(code.substring(open, close + 1));
        if (k <= 0) {
            return null;
        }
        String tail = sql.substring(close + 1).replaceFirst(";\\s*$", "");
        return new InsertRewriter(sql.substring(0, open), sql.substring(open, close + 1), tail, k);
    }

    /**
     * The tuple's highest placeholder, provided they are exactly $1..$k (each used, none skipped);
     * otherwise -1, because rows could not be renumbered by a fixed offset.
     */
    private static int placeholders(String tupleCode) {
        boolean[] seen = new boolean[MAX_PARAMS + 1];
        int max = 0;
        for (int i = 0; i < tupleCode.length(); i++) {
            if (tupleCode.charAt(i) == '$') {
                int j = i + 1;
                while (j < tupleCode.length() && Character.isDigit(tupleCode.charAt(j))) {
                    j++;
                }
                if (j == i + 1) {
                    return -1;
                }
                int n = Integer.parseInt(tupleCode.substring(i + 1, Math.min(j, i + 7)));
                if (n < 1 || n > MAX_PARAMS) {
                    return -1;
                }
                seen[n] = true;
                max = Math.max(max, n);
                i = j - 1;
            }
        }
        for (int n = 1; n <= max; n++) {
            if (!seen[n]) {
                return -1;
            }
        }
        return max;
    }

    /** {@code tuple} with every {@code $n} outside literals shifted by {@code offset}. */
    private static String renumber(String tuple, int offset) {
        if (offset == 0) {
            return tuple;
        }
        String code = blankLiteralsAndComments(tuple);
        StringBuilder sb = new StringBuilder(tuple.length() + 8);
        for (int i = 0; i < tuple.length(); i++) {
            char c = tuple.charAt(i);
            if (code.charAt(i) == '$') {
                int j = i + 1;
                while (j < tuple.length() && Character.isDigit(tuple.charAt(j))) {
                    j++;
                }
                sb.append('$').append(Integer.parseInt(tuple.substring(i + 1, j)) + offset);
                i = j - 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Index of {@code word} as a whole word in {@code lower}, or -1. */
    private static int keyword(String lower, String word) {
        int from = 0;
        while (true) {
            int i = lower.indexOf(word, from);
            if (i < 0) {
                return -1;
            }
            boolean before = i == 0 || !isIdentChar(lower.charAt(i - 1));
            int end = i + word.length();
            boolean after = end >= lower.length() || !isIdentChar(lower.charAt(end));
            if (before && after) {
                return i;
            }
            from = i + 1;
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Index of the ')' closing the '(' at {@code open}, or -1. */
    private static int matching(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * {@code sql} with every string literal, quoted identifier and comment replaced by spaces of
     * the same length, so positions still line up but nothing inside them is mistaken for SQL.
     */
    static String blankLiteralsAndComments(String sql) {
        char[] out = sql.toCharArray();
        int len = out.length;
        for (int i = 0; i < len; i++) {
            char ch = sql.charAt(i);
            if (ch == '\'' || ch == '"') {
                int start = i;
                for (i++; i < len; i++) {
                    if (sql.charAt(i) == ch) {
                        if (i + 1 < len && sql.charAt(i + 1) == ch) {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                blank(out, start, Math.min(i, len - 1));
            } else if (ch == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int start = i;
                while (i < len && sql.charAt(i) != '\n') {
                    i++;
                }
                blank(out, start, i - 1);
            } else if (ch == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int start = i;
                for (i += 2; i < len; i++) {
                    if (sql.charAt(i) == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                        i++;
                        break;
                    }
                }
                blank(out, start, Math.min(i, len - 1));
            }
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        for (int i = from; i <= to; i++) {
            out[i] = ' ';
        }
    }
}
