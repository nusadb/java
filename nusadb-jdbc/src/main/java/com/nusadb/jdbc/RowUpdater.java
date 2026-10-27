package com.nusadb.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the changes made through an updatable result set ({@code CONCUR_UPDATABLE}) back to its
 * table. The server does not say which table a result column comes from, so the query is read: it
 * must select from one table, with no join, grouping or set operation, and select the table's whole
 * primary key. A result column belongs to the table column of the same name. Each change is one
 * {@code UPDATE}, {@code INSERT} or {@code DELETE} on the connection (inside its transaction, when one
 * is open) that identifies the row by its primary key and returns the row as stored.
 */
final class RowUpdater {
    private final NusaConnection connection;
    private final String sql;
    private boolean resolved;
    /** Why the result set cannot be updated, or null when it can. */
    private String problem;
    /** The table as written in DML ({@code "schema"."name"}). */
    private String table;
    /** For each result column (0-based) its table column, or null when it is not one. */
    private String[] tableColumns;
    /** The result columns (0-based) holding the primary key. */
    private int[] keyColumns;

    RowUpdater(NusaConnection connection, String sql) {
        this.connection = connection;
        this.sql = sql;
    }

    // --- reading the query ---

    /** A schema-qualified table name, each part as stored (unquoted names folded to lower case). */
    record TableRef(String schema, String name) {}

    private static final Set<String> AFTER_TABLE = Set.of("where", "order", "limit", "offset", "for", "fetch");
    private static final Set<String> REJECTED = Set.of("join", "group", "having", "union", "intersect",
            "except", "window", "distinct", "lateral", "natural", "cross");

    /** A token of the query: a word, a quoted identifier, or one punctuation character. */
    private record Token(String text, boolean quoted, int depth) {
        boolean word(String w) {
            return !quoted && text.equalsIgnoreCase(w);
        }
    }

    /** The query's table, or a reason it has not exactly one; {@code error[0]} gets the reason. */
    static TableRef table(String sql, String[] error) {
        List<Token> tokens = tokens(sql);
        int i = 0;
        if (tokens.isEmpty() || !tokens.get(0).word("select")) {
            error[0] = "the query is not a SELECT";
            return null;
        }
        int from = -1;
        for (Token t : tokens) {
            if (t.depth() == 0 && !t.quoted() && REJECTED.contains(t.text().toLowerCase(Locale.ROOT))) {
                error[0] = "the query uses " + t.text().toUpperCase(Locale.ROOT);
                return null;
            }
        }
        for (i = 1; i < tokens.size(); i++) {
            if (tokens.get(i).depth() == 0 && tokens.get(i).word("from")) {
                from = i;
                break;
            }
        }
        if (from < 0 || from + 1 >= tokens.size()) {
            error[0] = "the query reads no table";
            return null;
        }
        i = from + 1;
        Token first = tokens.get(i);
        if (!name(first)) {
            error[0] = "the query does not read a plain table";
            return null;
        }
        String schema = null;
        String name = identifier(first);
        i++;
        if (i + 1 < tokens.size() && tokens.get(i).text().equals(".") && name(tokens.get(i + 1))) {
            schema = name;
            name = identifier(tokens.get(i + 1));
            i += 2;
        }
        if (i < tokens.size() && tokens.get(i).word("as")) {
            i++;
        }
        if (i < tokens.size() && name(tokens.get(i)) && !AFTER_TABLE.contains(tokens.get(i).text().toLowerCase(Locale.ROOT))) {
            i++; // the table's alias
        }
        if (i < tokens.size()) {
            Token next = tokens.get(i);
            boolean end = next.text().equals(";") && i == tokens.size() - 1;
            if (!end && (next.quoted() || !AFTER_TABLE.contains(next.text().toLowerCase(Locale.ROOT)))) {
                error[0] = "the query reads more than one table";
                return null;
            }
        }
        return new TableRef(schema, name);
    }

    private static boolean name(Token t) {
        if (t.quoted()) {
            return true;
        }
        char c = t.text().charAt(0);
        return Character.isLetter(c) || c == '_';
    }

    private static String identifier(Token t) {
        return t.quoted() ? t.text() : t.text().toLowerCase(Locale.ROOT);
    }

    private static List<Token> tokens(String sql) {
        List<Token> out = new ArrayList<>();
        int depth = 0;
        int n = sql.length();
        for (int i = 0; i < n; ) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'' ) {
                int j = i + 1;
                while (j < n && !(sql.charAt(j) == '\'' && (j + 1 >= n || sql.charAt(j + 1) != '\''))) {
                    j += sql.charAt(j) == '\'' ? 2 : 1;
                }
                out.add(new Token("'", false, depth));
                i = j + 1;
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == '"') {
                        if (j + 1 < n && sql.charAt(j + 1) == '"') {
                            sb.append('"');
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    sb.append(sql.charAt(j++));
                }
                out.add(new Token(sb.toString(), true, depth));
                i = j + 1;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '$')) {
                    j++;
                }
                out.add(new Token(sql.substring(i, j), false, depth));
                i = j;
            } else {
                if (c == ')') {
                    depth--;
                }
                out.add(new Token(String.valueOf(c), false, depth));
                if (c == '(') {
                    depth++;
                }
                i++;
            }
        }
        return out;
    }

    // --- resolving the table ---

    /** Finds the table, its columns and its key once; afterwards {@link #check} reports a problem. */
    private void resolve(List<String> labels) throws SQLException {
        if (resolved) {
            return;
        }
        resolved = true;
        String[] error = new String[1];
        TableRef ref = table(sql, error);
        if (ref == null) {
            problem = error[0];
            return;
        }
        String schema = ref.schema() != null ? ref.schema() : connection.getSchema();
        java.sql.DatabaseMetaData md = connection.getMetaData();
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = md.getColumns(null, schema, ref.name(), "%")) {
            while (rs.next()) {
                if (ref.name().equals(rs.getString("TABLE_NAME")) && schema.equals(rs.getString("TABLE_SCHEM"))) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        if (columns.isEmpty()) {
            problem = "table " + schema + "." + ref.name() + " was not found";
            return;
        }
        List<String> keys = new ArrayList<>();
        try (ResultSet rs = md.getPrimaryKeys(null, schema, ref.name())) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        if (keys.isEmpty()) {
            problem = "table " + ref.name() + " has no primary key";
            return;
        }
        tableColumns = new String[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            tableColumns[i] = match(columns, labels.get(i));
        }
        keyColumns = new int[keys.size()];
        for (int k = 0; k < keys.size(); k++) {
            keyColumns[k] = -1;
            for (int i = 0; i < tableColumns.length; i++) {
                if (keys.get(k).equals(tableColumns[i])) {
                    keyColumns[k] = i;
                    break;
                }
            }
            if (keyColumns[k] < 0) {
                problem = "the query does not select primary key column " + keys.get(k) + " of " + ref.name();
                return;
            }
        }
        table = quote(schema) + "." + quote(ref.name());
    }

    private static String match(List<String> columns, String label) {
        if (columns.contains(label)) {
            return label;
        }
        for (String c : columns) {
            if (c.equalsIgnoreCase(label)) {
                return c;
            }
        }
        return null;
    }

    private static String quote(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Throws when the result set cannot be written back. */
    void check(List<String> labels) throws SQLException {
        resolve(labels);
        if (problem != null) {
            throw new SQLException("nusadb: the result set is not updatable: " + problem
                    + " (it must select from one table and include its primary key)", "24000");
        }
    }

    /** Throws when result column {@code index} (0-based) is not a column of the table. */
    void checkColumn(List<String> labels, int index) throws SQLException {
        check(labels);
        if (tableColumns[index] == null) {
            throw new SQLException("nusadb: result column " + labels.get(index)
                    + " is not a column of the table and cannot be updated", "24000");
        }
    }

    // --- writing ---

    /** {@code UPDATE} the row; returns the row as stored (with every result column). */
    List<byte[]> update(List<String> labels, List<byte[]> row, Map<Integer, Object> changes) throws SQLException {
        check(labels);
        StringBuilder q = new StringBuilder("UPDATE ").append(table).append(" SET ");
        List<Object> values = new ArrayList<>();
        String sep = "";
        for (Map.Entry<Integer, Object> e : changes.entrySet()) {
            values.add(e.getValue());
            q.append(sep).append(quote(tableColumns[e.getKey()])).append(" = $").append(values.size());
            sep = ", ";
        }
        where(q, row, values);
        return returning(q, values, labels, row, "the row to update");
    }

    /** {@code INSERT} a row from {@code values}; returns it as stored. */
    List<byte[]> insert(List<String> labels, Map<Integer, Object> changes) throws SQLException {
        check(labels);
        StringBuilder q = new StringBuilder("INSERT INTO ").append(table);
        List<Object> values = new ArrayList<>();
        if (changes.isEmpty()) {
            q.append(" DEFAULT VALUES");
        } else {
            StringBuilder cols = new StringBuilder();
            StringBuilder params = new StringBuilder();
            for (Map.Entry<Integer, Object> e : changes.entrySet()) {
                values.add(e.getValue());
                cols.append(cols.length() == 0 ? "" : ", ").append(quote(tableColumns[e.getKey()]));
                params.append(params.length() == 0 ? "$" : ", $").append(values.size());
            }
            q.append(" (").append(cols).append(") VALUES (").append(params).append(')');
        }
        return returning(q, values, labels, null, "the inserted row");
    }

    /** {@code DELETE} the row. */
    void delete(List<String> labels, List<byte[]> row) throws SQLException {
        check(labels);
        StringBuilder q = new StringBuilder("DELETE FROM ").append(table);
        List<Object> values = new ArrayList<>();
        where(q, row, values);
        returning(q, values, labels, row, "the row to delete");
    }

    /** The row as stored now. */
    List<byte[]> refresh(List<String> labels, List<byte[]> row) throws SQLException {
        check(labels);
        StringBuilder q = new StringBuilder("SELECT * FROM ").append(table);
        List<Object> values = new ArrayList<>();
        where(q, row, values);
        return run(q.toString(), values, labels, row, "the row to refresh");
    }

    /** The key as read: each value is sent as its text, for the server to type like the column. */
    private void where(StringBuilder q, List<byte[]> row, List<Object> values) {
        String sep = " WHERE ";
        for (int k : keyColumns) {
            byte[] cell = row.get(k);
            values.add(new Untyped(cell == null ? null : new String(cell, StandardCharsets.UTF_8)));
            q.append(sep).append(quote(tableColumns[k])).append(" = $").append(values.size());
            sep = " AND ";
        }
    }

    private List<byte[]> returning(StringBuilder q, List<Object> values, List<String> labels, List<byte[]> row,
            String what) throws SQLException {
        return run(q.append(" RETURNING *").toString(), values, labels, row, what);
    }

    /** A key value read from the result, bound untyped. */
    record Untyped(String text) {}

    private List<byte[]> run(String statement, List<Object> values, List<String> labels, List<byte[]> row,
            String what) throws SQLException {
        try (NusaPreparedStatement ps = new NusaPreparedStatement(connection, statement)) {
            for (int i = 0; i < values.size(); i++) {
                Object v = values.get(i);
                if (v instanceof Untyped u) {
                    ps.setUntyped(i + 1, u.text());
                } else if (v == UpdatingResultSet.NULL) {
                    ps.setNull(i + 1, java.sql.Types.NULL);
                } else {
                    ps.setObject(i + 1, v);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                NusaResultSet stored = (NusaResultSet) rs;
                if (!stored.next()) {
                    throw new SQLException("nusadb: " + what + " was not found: another transaction changed"
                            + " or deleted it, or its key was changed", "24000");
                }
                Map<String, Integer> at = new LinkedHashMap<>();
                List<String> storedColumns = stored.columnLabels();
                for (int i = 0; i < storedColumns.size(); i++) {
                    at.putIfAbsent(storedColumns.get(i), i);
                }
                List<byte[]> out = new ArrayList<>(labels.size());
                List<byte[]> cells = stored.currentCells();
                for (int i = 0; i < labels.size(); i++) {
                    Integer j = tableColumns[i] == null ? null : at.get(tableColumns[i]);
                    out.add(j != null ? cells.get(j) : row != null ? row.get(i) : null);
                }
                return out;
            }
        }
    }
}
