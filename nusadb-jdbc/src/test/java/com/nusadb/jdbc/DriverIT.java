package com.nusadb.jdbc;

import com.nusadb.testing.NusaTestServer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for the NusaDB JDBC driver against a real server, provided by
 * {@link NusaTestServer} (see there for how to choose it). Run with {@code mvn verify -Pit}.
 *
 * <p>The tests share one database and run in a fixed order, as they always have: later checks (the
 * metadata introspection in particular) see the tables earlier ones created.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DriverIT {
    private static NusaTestServer server;

    @BeforeAll
    static void startServer() throws Exception {
        Class.forName("com.nusadb.jdbc.NusaDriver");
        server = NusaTestServer.get();
    }

    @Test
    @Order(1)
    @DisplayName("simple query round trip")
    void simpleQuery() throws Exception {
        simpleQuery(server);
    }

    @Test
    @Order(2)
    @DisplayName("parameterised query with NULL")
    void parameterised() throws Exception {
        parameterised(server);
    }

    @Test
    @Order(3)
    @DisplayName("prepared statement reused")
    void prepared() throws Exception {
        prepared(server);
    }

    @Test
    @Order(4)
    @DisplayName("batch updates (statement + prepared)")
    void batchUpdates() throws Exception {
        batchUpdates(server);
    }

    @Test
    @Order(5)
    @DisplayName("question-mark placeholders")
    void questionMarkPlaceholders() throws Exception {
        questionMarkPlaceholders(server);
    }

    @Test
    @Order(6)
    @DisplayName("server error surfaces")
    void serverError() throws Exception {
        serverError(server);
    }

    @Test
    @Order(7)
    @DisplayName("database metadata introspection")
    void databaseMetadata() throws Exception {
        databaseMetadata(server);
    }

    @Test
    @Order(8)
    @DisplayName("generated keys via RETURNING")
    void generatedKeys() throws Exception {
        generatedKeys(server);
    }

    @Test
    @Order(9)
    @DisplayName("copy bulk load and export")
    void copyBulk() throws Exception {
        copyBulk(server);
    }

    @Test
    @Order(10)
    @DisplayName("bytea round trip")
    void byteaRoundTrip() throws Exception {
        byteaRoundTrip(server);
    }

    @Test
    @Order(11)
    @DisplayName("getObject returns typed values")
    void getObjectTyped() throws Exception {
        getObjectTyped(server);
    }

    @Test
    @Order(12)
    @DisplayName("transaction commit persists")
    void transactionCommit() throws Exception {
        transactionCommit(server);
    }

    @Test
    @Order(13)
    @DisplayName("transaction rollback discards")
    void transactionRollback() throws Exception {
        transactionRollback(server);
    }

    @Test
    @Order(14)
    @DisplayName("savepoint rollback and release")
    void savepoints() throws Exception {
        savepoints(server);
    }

    @Test
    @Order(15)
    @DisplayName("listen/notify self-delivery")
    void listenNotify() throws Exception {
        listenNotify(server);
    }

    @Test
    @Order(16)
    @DisplayName("query surface round trip")
    void querySurface() throws Exception {
        querySurface(server);
    }

    @Test
    @Order(17)
    @DisplayName("array binding (createArrayOf / setArray)")
    void arrayBinding() throws Exception {
        arrayBinding(server);
    }

    @Test
    @Order(18)
    @DisplayName("scram correct password")
    void scramOk() throws Exception {
        scramOk(server);
    }

    @Test
    @Order(19)
    @DisplayName("scram wrong password")
    void scramWrong() throws Exception {
        scramWrong(server);
    }

    private static void simpleQuery(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_simple (id INT NOT NULL, name TEXT)");
                int n = st.executeUpdate("INSERT INTO jdbc_simple VALUES (5, 'alice')");
                expect(n == 1, "insert count == 1");
                try (ResultSet rs = st.executeQuery("SELECT id, name FROM jdbc_simple")) {
                    // Protocol 1.1 typed metadata: ResultSetMetaData reports column types.
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    expect("INT".equals(md.getColumnTypeName(1)), "column 1 type name is INT");
                    expect(md.getColumnType(1) == java.sql.Types.BIGINT, "column 1 type is BIGINT");
                    expect("TEXT".equals(md.getColumnTypeName(2)), "column 2 type name is TEXT");
                    expect(rs.next(), "one row");
                    expect(rs.getInt(1) == 5, "id == 5");
                    expect("alice".equals(rs.getString("name")), "name == alice");
                    expect(!rs.next(), "no more rows");
                }
            }
        }
    }

    private static void getObjectTyped(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_obj (b BOOL, i INT, f FLOAT, n NUMERIC(10,2), "
                    + "d DATE, ts TIMESTAMP, u UUID)");
                st.execute("INSERT INTO jdbc_obj VALUES (true, 42, 1.5, 12.34, DATE '2024-01-15', "
                    + "TIMESTAMP '2024-01-15 10:30:00', "
                    + "UUID '00000000-0000-0000-0000-000000000001')");
                try (ResultSet rs = st.executeQuery("SELECT b, i, f, n, d, ts, u FROM jdbc_obj")) {
                    expect(rs.next(), "one row");
                    // getObject returns the Java type matching each column's SQL type (JDBC fidelity).
                    expect(rs.getObject(1) instanceof Boolean && (Boolean) rs.getObject(1),
                        "BOOL -> Boolean true");
                    expect(rs.getObject(2) instanceof Long && (Long) rs.getObject(2) == 42L,
                        "INT -> Long 42");
                    expect(rs.getObject(3) instanceof Double && (Double) rs.getObject(3) == 1.5,
                        "FLOAT -> Double 1.5");
                    expect(rs.getObject(4) instanceof java.math.BigDecimal
                        && ((java.math.BigDecimal) rs.getObject(4)).compareTo(new java.math.BigDecimal("12.34")) == 0,
                        "NUMERIC -> BigDecimal 12.34");
                    expect(rs.getObject(5) instanceof java.sql.Date, "DATE -> java.sql.Date");
                    expect(rs.getObject(6) instanceof java.sql.Timestamp, "TIMESTAMP -> Timestamp");
                    expect(rs.getObject(7) instanceof java.util.UUID
                        && rs.getObject(7).toString().equals("00000000-0000-0000-0000-000000000001"),
                        "UUID -> java.util.UUID");
                }
                // A NULL column returns null from getObject and sets wasNull().
                st.execute("CREATE TABLE jdbc_obj_null (i INT, b BOOL)");
                st.execute("INSERT INTO jdbc_obj_null VALUES (NULL, NULL)");
                try (ResultSet rs = st.executeQuery("SELECT i, b FROM jdbc_obj_null")) {
                    expect(rs.next(), "one row");
                    expect(rs.getObject(1) == null && rs.wasNull(), "NULL INT -> null");
                    expect(rs.getObject(2) == null, "NULL BOOL -> null");
                }
            }
        }
    }

    private static void parameterised(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_params (id INT NOT NULL, name TEXT)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO jdbc_params VALUES ($1, $2)")) {
                ps.setInt(1, 1);
                ps.setString(2, "alice");
                ps.executeUpdate();
                ps.setInt(1, 2);
                ps.setNull(2, java.sql.Types.VARCHAR);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name FROM jdbc_params WHERE id = $1")) {
                ps.setInt(1, 2);
                try (ResultSet rs = ps.executeQuery()) {
                    expect(rs.next(), "one row");
                    expect(rs.getInt("id") == 2, "id == 2");
                    expect(rs.getString("name") == null && rs.wasNull(), "name is NULL");
                }
            }
        }
    }

    private static void prepared(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_prep (id INT NOT NULL)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO jdbc_prep VALUES ($1)")) {
                for (int i = 1; i <= 3; i++) {
                    ps.setInt(1, i);
                    ps.executeUpdate();
                }
            }
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT id FROM jdbc_prep")) {
                int count = 0;
                while (rs.next()) {
                    count++;
                }
                expect(count == 3, "three rows");
            }
        }
    }

    private static void batchUpdates(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_batch (id INT NOT NULL, name TEXT)");
            }
            // Statement batch: a list of distinct SQL strings.
            try (Statement st = c.createStatement()) {
                st.addBatch("INSERT INTO jdbc_batch VALUES (1, 'a')");
                st.addBatch("INSERT INTO jdbc_batch VALUES (2, 'b')");
                int[] counts = st.executeBatch();
                expect(counts.length == 2, "statement batch returns 2 counts");
                expect(counts[0] == 1 && counts[1] == 1, "each statement affected 1 row");
            }
            // PreparedStatement batch: one prepared SQL, many parameter sets.
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO jdbc_batch VALUES ($1, $2)")) {
                for (int i = 3; i <= 5; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "n" + i);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                expect(counts.length == 3, "prepared batch returns 3 counts");
                expect(counts[0] == 1 && counts[1] == 1 && counts[2] == 1, "each param-set affected 1 row");
                // executeBatch clears the batch: a second call with no additions runs nothing.
                expect(ps.executeBatch().length == 0, "batch cleared after execution");
                // addBatch(String) is illegal on a PreparedStatement (JDBC spec).
                boolean threw = false;
                try {
                    ps.addBatch("INSERT INTO jdbc_batch VALUES (9, 'x')");
                } catch (SQLException e) {
                    threw = true;
                }
                expect(threw, "addBatch(String) rejected on PreparedStatement");
            }
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT count(*) FROM jdbc_batch")) {
                expect(rs.next() && rs.getInt(1) == 5, "all five batched rows persisted");
            }
        }
    }

    private static void questionMarkPlaceholders(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_qm (id INT NOT NULL, name TEXT)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO jdbc_qm VALUES (?, ?)")) {
                ps.setInt(1, 7);
                ps.setString(2, "bob");
                expect(ps.executeUpdate() == 1, "insert with ? markers");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM jdbc_qm WHERE id = ?")) {
                ps.setInt(1, 7);
                try (ResultSet rs = ps.executeQuery()) {
                    expect(rs.next(), "row found via ? marker");
                    expect("bob".equals(rs.getString(1)), "name == bob");
                }
            }
        }
    }

    private static void databaseMetadata(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE meta_t (id INT NOT NULL, name TEXT, active BOOL)");
            }
            java.sql.DatabaseMetaData md = c.getMetaData();
            expect("NusaDB".equals(md.getDatabaseProductName()), "product name is NusaDB");
            expect("\"".equals(md.getIdentifierQuoteString()), "identifier quote is double-quote");

            // getTables: the table appears with a table type (what a tool's schema tree reads).
            boolean foundTable = false;
            try (ResultSet rs = md.getTables(null, null, "meta_t", null)) {
                while (rs.next()) {
                    if ("meta_t".equals(rs.getString("TABLE_NAME"))) {
                        foundTable = true;
                        expect(rs.getString("TABLE_TYPE") != null, "table has a TABLE_TYPE");
                    }
                }
            }
            expect(foundTable, "getTables lists meta_t");

            // getColumns: every column with a name, type name, ordinal, and a column size.
            List<String> cols = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, null, "meta_t", null)) {
                while (rs.next()) {
                    cols.add(rs.getString("COLUMN_NAME"));
                    expect(rs.getString("TYPE_NAME") != null, "column has a TYPE_NAME");
                    expect(rs.getInt("ORDINAL_POSITION") >= 1, "ordinal position is 1-based");
                    if ("id".equals(rs.getString("COLUMN_NAME"))) {
                        expect(rs.getInt("COLUMN_SIZE") >= 1, "INT column has a positive COLUMN_SIZE");
                    }
                }
            }
            expect(cols.contains("id") && cols.contains("name") && cols.contains("active"),
                    "getColumns lists id, name, active");

            // getColumns column name filter with an escaped wildcard: `id` matches, `i_` would too
            // unescaped, but `i\_` (escaped) is a literal underscore and must NOT match `id`.
            try (ResultSet rs = md.getColumns(null, null, "meta_t", "i\\_")) {
                expect(!rs.next(), "escaped underscore is literal (does not match 'id')");
            }

            // getColumns reflects the declared length/precision: VARCHAR(n) COLUMN_SIZE = n;
            // NUMERIC(p,s) COLUMN_SIZE = p and DECIMAL_DIGITS = s.
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE meta_sizes (vc VARCHAR(10), amount NUMERIC(8, 2))");
            }
            boolean sawVc = false;
            boolean sawNum = false;
            try (ResultSet rs = md.getColumns(null, null, "meta_sizes", null)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if ("vc".equals(col)) {
                        sawVc = true;
                        expect(rs.getInt("COLUMN_SIZE") == 10, "VARCHAR(10) COLUMN_SIZE is 10");
                    } else if ("amount".equals(col)) {
                        sawNum = true;
                        expect(rs.getInt("COLUMN_SIZE") == 8, "NUMERIC(8,2) COLUMN_SIZE is 8");
                        expect(rs.getInt("DECIMAL_DIGITS") == 2, "NUMERIC(8,2) DECIMAL_DIGITS is 2");
                    }
                }
            }
            expect(sawVc && sawNum, "getColumns reflects VARCHAR(10) and NUMERIC(8,2)");

            // getColumns reports COLUMN_DEF (the DEFAULT SQL) and derives IS_AUTOINCREMENT from a
            // SERIAL column's nextval(...) default.
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE meta_def (id SERIAL, status TEXT DEFAULT 'active', n INT)");
            }
            boolean sawSerial = false;
            boolean sawDef = false;
            boolean sawPlain = false;
            try (ResultSet rs = md.getColumns(null, null, "meta_def", null)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if ("id".equals(col)) {
                        sawSerial = true;
                        expect("YES".equals(rs.getString("IS_AUTOINCREMENT")), "SERIAL id is auto-increment");
                        String def = rs.getString("COLUMN_DEF");
                        expect(def != null && def.startsWith("nextval("), "SERIAL COLUMN_DEF is nextval(...)");
                    } else if ("status".equals(col)) {
                        sawDef = true;
                        expect("'active'".equals(rs.getString("COLUMN_DEF")), "status COLUMN_DEF is 'active'");
                        expect("NO".equals(rs.getString("IS_AUTOINCREMENT")), "status is not auto-increment");
                    } else if ("n".equals(col)) {
                        sawPlain = true;
                        expect(rs.getString("COLUMN_DEF") == null, "n has no COLUMN_DEF");
                    }
                }
            }
            expect(sawSerial && sawDef && sawPlain, "getColumns reports COLUMN_DEF / IS_AUTOINCREMENT");

            // The schema-tree scaffolding calls must all return a usable result set.
            try (ResultSet rs = md.getSchemas()) {
                expect(rs.getMetaData().getColumnCount() >= 1, "getSchemas has columns");
            }
            try (ResultSet rs = md.getCatalogs()) {
                expect(rs.getMetaData().getColumnCount() >= 1, "getCatalogs has columns");
                boolean sawConnected = false;
                while (rs.next()) {
                    if ("nusadb".equals(rs.getString("TABLE_CAT"))) {
                        sawConnected = true;
                    }
                }
                expect(sawConnected, "getCatalogs lists the whole cluster (connected db present)");
            }
            int typeRows = 0;
            try (ResultSet rs = md.getTableTypes()) {
                while (rs.next()) {
                    typeRows++;
                }
            }
            expect(typeRows >= 1, "getTableTypes returns rows");
            try (ResultSet rs = md.getTypeInfo()) {
                expect(rs.next(), "getTypeInfo returns at least one type");
            }
            // getPrimaryKeys / getIndexInfo are answered from information_schema.
            // A key-less table reports none (empty but correctly shaped) ...
            try (ResultSet rs = md.getPrimaryKeys(null, null, "meta_t")) {
                expect(!rs.next(), "a key-less table reports no primary keys");
            }
            // ... while a table with a composite PK and a secondary index reports them.
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE meta_pk (a INT NOT NULL, b INT NOT NULL, c TEXT, "
                        + "PRIMARY KEY (a, b))");
                st.execute("CREATE INDEX meta_pk_c_idx ON meta_pk (c)");
            }
            // Composite PRIMARY KEY (a, b): two rows, KEY_SEQ 1->a then 2->b, sharing one PK_NAME.
            List<String> pkCols = new ArrayList<>();
            String pkName = null;
            try (ResultSet rs = md.getPrimaryKeys(null, null, "meta_pk")) {
                while (rs.next()) {
                    pkCols.add(rs.getString("COLUMN_NAME"));
                    expect(rs.getInt("KEY_SEQ") == pkCols.size(), "KEY_SEQ is 1-based and in key order");
                    String name = rs.getString("PK_NAME");
                    expect(name != null && !name.isEmpty(), "PK_NAME is reported");
                    if (pkName == null) {
                        pkName = name;
                    }
                    expect(name.equals(pkName), "all PK columns share one PK_NAME");
                }
            }
            expect(pkCols.equals(java.util.Arrays.asList("a", "b")),
                    "composite PK reports columns a, b in key order");
            // getIndexInfo lists the secondary index; NON_UNIQUE is true for a plain CREATE INDEX.
            boolean foundIdx = false;
            try (ResultSet rs = md.getIndexInfo(null, null, "meta_pk", false, true)) {
                while (rs.next()) {
                    if ("meta_pk_c_idx".equals(rs.getString("INDEX_NAME"))) {
                        foundIdx = true;
                        expect("true".equals(rs.getString("NON_UNIQUE")), "CREATE INDEX is non-unique");
                        expect("c".equals(rs.getString("COLUMN_NAME")), "index is on column c");
                        expect(rs.getInt("ORDINAL_POSITION") == 1, "single-column index ordinal is 1");
                    }
                }
            }
            expect(foundIdx, "getIndexInfo lists the secondary index meta_pk_c_idx");
            // The unique-only filter drops the non-unique secondary index.
            try (ResultSet rs = md.getIndexInfo(null, null, "meta_pk", true, true)) {
                boolean sawNonUnique = false;
                while (rs.next()) {
                    if ("meta_pk_c_idx".equals(rs.getString("INDEX_NAME"))) {
                        sawNonUnique = true;
                    }
                }
                expect(!sawNonUnique, "unique-only getIndexInfo excludes the non-unique index");
            }
        }
    }

    private static void generatedKeys(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE gk (id INT NOT NULL, name TEXT)");
            }
            // Statement + RETURN_GENERATED_KEYS: the driver appends RETURNING * and exposes the row.
            try (Statement st = c.createStatement()) {
                int n = st.executeUpdate("INSERT INTO gk VALUES (1, 'a')",
                        Statement.RETURN_GENERATED_KEYS);
                expect(n == 1, "executeUpdate reports one affected row");
                try (ResultSet keys = st.getGeneratedKeys()) {
                    expect(keys.next(), "generated keys has a row");
                    expect(keys.getInt(1) == 1, "generated key id == 1");
                    expect(!keys.next(), "exactly one generated-keys row");
                }
            }
            // A column-name list narrows RETURNING to that column.
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO gk VALUES (2, 'b')", new String[] {"id"});
                try (ResultSet keys = st.getGeneratedKeys()) {
                    expect(keys.next() && keys.getInt("id") == 2, "named generated key id == 2");
                }
            }
            // No key request -> getGeneratedKeys is empty but correctly shaped.
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO gk VALUES (3, 'c')");
                try (ResultSet keys = st.getGeneratedKeys()) {
                    expect(!keys.next(), "no generated keys when not requested");
                }
            }
            // PreparedStatement + RETURN_GENERATED_KEYS.
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO gk VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, 4);
                ps.setString(2, "d");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    expect(keys.next() && keys.getInt(1) == 4, "prepared generated key id == 4");
                }
            }
            // Regression: a value containing the word "returning" must not fool the
            // already-has-RETURNING guard, so RETURNING is still appended and the key captured.
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO gk VALUES (5, 'returning x')",
                        Statement.RETURN_GENERATED_KEYS);
                try (ResultSet keys = st.getGeneratedKeys()) {
                    expect(keys.next() && keys.getInt(1) == 5,
                            "a literal 'returning' does not suppress key capture");
                }
            }
            // A user-supplied RETURNING is used as-is (not doubled) and still surfaces as keys.
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO gk VALUES (6, 'f') RETURNING name",
                        Statement.RETURN_GENERATED_KEYS);
                try (ResultSet keys = st.getGeneratedKeys()) {
                    expect(keys.next() && "f".equals(keys.getString("name")),
                            "an explicit RETURNING is captured as the generated keys");
                }
            }
            // A multi-row INSERT yields one generated-keys row per inserted row, in order.
            try (Statement st = c.createStatement()) {
                int n = st.executeUpdate("INSERT INTO gk VALUES (7, 'g'), (8, 'h')",
                        Statement.RETURN_GENERATED_KEYS);
                expect(n == 2, "multi-row insert reports two affected rows");
                try (ResultSet keys = st.getGeneratedKeys()) {
                    expect(keys.next() && keys.getInt(1) == 7, "first generated key id == 7");
                    expect(keys.next() && keys.getInt(1) == 8, "second generated key id == 8");
                    expect(!keys.next(), "exactly two generated-keys rows");
                }
            }
            // A SERIAL column is assigned by the server and surfaced through getGeneratedKeys.
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE gk_serial (id SERIAL PRIMARY KEY, name TEXT)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gk_serial (name) VALUES (?)", new String[] {"id"})) {
                ps.setString(1, "auto");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    expect(keys.next(), "serial generated key present");
                    expect(keys.getInt("id") >= 1, "serial key is a positive integer");
                }
            }
        }
    }

    private static void copyBulk(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE copy_t (id INT NOT NULL, name TEXT, PRIMARY KEY (id))");
            }
            NusaCopyManager copy = c.unwrap(NusaCopyManager.class);

            // COPY FROM STDIN: tab-delimited rows, \N for NULL (the server's default text format).
            byte[] data = "1\talice\n2\t\\N\n3\tcarol\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long loaded = copy.copyIn(
                    "COPY copy_t FROM STDIN", new java.io.ByteArrayInputStream(data));
            expect(loaded == 3, "COPY FROM loaded three rows");

            // The rows landed and the NULL round-tripped.
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT id, name FROM copy_t ORDER BY id")) {
                expect(rs.next() && rs.getInt(1) == 1 && "alice".equals(rs.getString(2)), "row 1");
                expect(rs.next() && rs.getString(2) == null && rs.wasNull(), "row 2 name is NULL");
                expect(rs.next() && "carol".equals(rs.getString(2)), "row 3");
            }

            // COPY TO STDOUT: bytes come back in the same text format.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            long exported = copy.copyOut("COPY copy_t TO STDOUT", out);
            expect(exported == 3, "COPY TO exported three rows");
            String text = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            expect(lines.length == 3, "three exported lines");
            expect("1\talice".equals(lines[0]), "exported row 1");
            expect("2\t\\N".equals(lines[1]), "NULL renders as the \\N marker");
            expect("3\tcarol".equals(lines[2]), "exported row 3");

            // A COPY the server refuses surfaces as an error, and the connection stays usable.
            boolean threw = false;
            try {
                copy.copyIn("COPY no_such_table FROM STDIN",
                        new java.io.ByteArrayInputStream(new byte[0]));
            } catch (SQLException e) {
                threw = true;
            }
            expect(threw, "COPY into a missing table errors");
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT count(*) FROM copy_t")) {
                expect(rs.next(), "connection usable after a refused COPY");
            }
        }
    }

    private static void arrayBinding(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE pb (t TEXT[], n INT[])");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pb VALUES ($1, $2)")) {
                // Standard JDBC array binding: createArrayOf + setArray.
                ps.setArray(1, c.createArrayOf("text", new Object[] {"a", "b,c", "{x}"}));
                // A Java array via setObject takes the same array-literal path.
                ps.setObject(2, new int[] {10, 20, 30});
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT t::text, n::text FROM pb")) {
                expect(rs.next(), "row present");
                expect("{a,\"b,c\",\"{x}\"}".equals(rs.getString(1)), "text array literal");
                expect("{10,20,30}".equals(rs.getString(2)), "int array literal");
            }
        }
    }

    private static void byteaRoundTrip(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE bt (id INT NOT NULL, data BYTEA, PRIMARY KEY (id))");
            }
            byte[] payload = {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef, 0x00, 0x7f};
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bt VALUES ($1, $2)")) {
                ps.setInt(1, 1);
                ps.setBytes(2, payload);
                ps.executeUpdate();
                // setObject(byte[]) takes the same BYTEA path.
                ps.setInt(1, 2);
                ps.setObject(2, new byte[] {});
                ps.executeUpdate();
                ps.setInt(1, 3);
                ps.setBytes(2, null);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT id, data FROM bt ORDER BY id")) {
                expect(rs.next() && java.util.Arrays.equals(rs.getBytes("data"), payload),
                        "BYTEA round-trips to the same bytes");
                expect(rs.next() && java.util.Arrays.equals(rs.getBytes(2), new byte[] {}),
                        "empty BYTEA round-trips to an empty array");
                expect(rs.next() && rs.getBytes("data") == null && rs.wasNull(), "NULL BYTEA");
            }
        }
    }

    private static void serverError(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                boolean threw = false;
                try {
                    st.executeQuery("SELECT * FROM ghost");
                } catch (SQLException e) {
                    threw = true;
                    expect(e.getSQLState() != null && e.getSQLState().length() == 5, "5-char SQLSTATE");
                }
                expect(threw, "missing table throws");
                // Connection still usable.
                expect(c.isValid(1), "connection valid after error");
            }
        }
    }

    private static void transactionCommit(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_tx_commit (id INT NOT NULL)");
            }
            c.setAutoCommit(false);
            expect(!c.getAutoCommit(), "autocommit off");
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO jdbc_tx_commit VALUES (1)");
            }
            c.commit();
            c.setAutoCommit(true);
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT id FROM jdbc_tx_commit")) {
                expect(rs.next(), "committed row visible");
                expect(rs.getInt(1) == 1, "committed id == 1");
                expect(!rs.next(), "exactly one row");
            }
        }
    }

    private static void transactionRollback(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_tx_rollback (id INT NOT NULL)");
                st.executeUpdate("INSERT INTO jdbc_tx_rollback VALUES (1)");
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO jdbc_tx_rollback VALUES (2)");
            }
            c.rollback();
            c.setAutoCommit(true);
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT id FROM jdbc_tx_rollback")) {
                expect(rs.next(), "pre-transaction row survives");
                expect(rs.getInt(1) == 1, "surviving id == 1");
                expect(!rs.next(), "rolled-back row is gone");
            }
        }
    }

    private static void savepoints(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE jdbc_sp (id INT NOT NULL)");
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO jdbc_sp VALUES (1)");
                java.sql.Savepoint sp = c.setSavepoint("sp1");
                expect("sp1".equals(sp.getSavepointName()), "named savepoint reports its name");
                st.executeUpdate("INSERT INTO jdbc_sp VALUES (2)");
                // Roll back to the savepoint: (2) is undone, (1) survives, the txn continues.
                c.rollback(sp);
                st.executeUpdate("INSERT INTO jdbc_sp VALUES (3)");
                // An unnamed savepoint that we then release (its work is kept).
                java.sql.Savepoint anon = c.setSavepoint();
                expect(anon.getSavepointId() > 0, "unnamed savepoint reports an id");
                st.executeUpdate("INSERT INTO jdbc_sp VALUES (4)");
                c.releaseSavepoint(anon);
            }
            c.commit();
            c.setAutoCommit(true);
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT id FROM jdbc_sp ORDER BY id")) {
                List<Integer> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
                expect(ids.equals(List.of(1, 3, 4)), "savepoint kept 1,3,4 (2 rolled back), got " + ids);
            }
            // Savepoints are illegal in autocommit mode (JDBC spec).
            boolean rejected = false;
            try {
                c.setSavepoint();
            } catch (SQLException expected) {
                rejected = true;
            }
            expect(rejected, "setSavepoint in autocommit rejected");
        }
    }

    private static void listenNotify(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            NusaListener l = c.unwrap(NusaListener.class);
            l.listen("jdbc_chan");
            // Self-delivery: NOTIFY on a channel this connection listens to comes back to it.
            l.notify("jdbc_chan", "hello");
            NusaNotification note = l.pollNotification(5000);
            expect(note != null, "a notification was delivered");
            expect("jdbc_chan".equals(note.getChannel()), "channel is jdbc_chan");
            expect("hello".equals(note.getPayload()), "payload is hello");
            // NOTIFY without a payload delivers an empty string.
            l.notify("jdbc_chan", null);
            NusaNotification empty = l.pollNotification(5000);
            expect(empty != null && empty.getPayload().isEmpty(), "empty payload");
            // After UNLISTEN, a further NOTIFY is not delivered (poll times out -> null).
            l.unlisten("jdbc_chan");
            l.notify("jdbc_chan", "ignored");
            expect(l.pollNotification(300) == null, "no notification after unlisten");
        }
    }

    /**
     * The driver is a SQL-text transport: any SELECT the server accepts is forwarded verbatim and
     * its typed rows decoded. This exercises the full read surface (ORDER BY / DISTINCT / LIMIT /
     * grouping / window / CTE / subquery / set ops / every join flavour / LATERAL) end to end via
     * raw {@code Statement.executeQuery}, asserting the row count returned over the wire per query.
     */
    private static void querySurface(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url())) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE surf_a (id INT NOT NULL, grp TEXT, v INT)");
                st.executeUpdate("INSERT INTO surf_a VALUES (1, 'a', 10)");
                st.executeUpdate("INSERT INTO surf_a VALUES (2, 'a', 30)");
                st.executeUpdate("INSERT INTO surf_a VALUES (3, 'b', 20)");
                st.executeUpdate("INSERT INTO surf_a VALUES (4, 'b', 20)");
                st.executeUpdate("INSERT INTO surf_a VALUES (5, 'a', 10)");
                st.execute("CREATE TABLE surf_b (id INT NOT NULL, a_id INT, tag TEXT)");
                st.executeUpdate("INSERT INTO surf_b VALUES (10, 1, 'p')");
                st.executeUpdate("INSERT INTO surf_b VALUES (11, 1, 'q')");
                st.executeUpdate("INSERT INTO surf_b VALUES (12, 2, 'r')");
            }
            try (Statement st = c.createStatement()) {
                expectRows(st, "SELECT id FROM surf_a ORDER BY v DESC, id", 5, "ORDER BY");
                expectRows(st, "SELECT DISTINCT v FROM surf_a ORDER BY v", 3, "DISTINCT");
                expectRows(st,
                        "SELECT DISTINCT ON (grp) grp, v FROM surf_a ORDER BY grp, v", 2,
                        "DISTINCT ON");
                expectRows(st, "SELECT id FROM surf_a ORDER BY id LIMIT 2", 2, "LIMIT");
                expectRows(st, "SELECT id FROM surf_a ORDER BY id LIMIT 2 OFFSET 3", 2, "OFFSET");
                expectRows(st,
                        "SELECT grp, count(*) FROM surf_a GROUP BY grp HAVING count(*) > 1 "
                                + "ORDER BY grp",
                        2, "GROUP BY / HAVING");
                expectRows(st,
                        "SELECT id, row_number() OVER (PARTITION BY grp ORDER BY v) FROM surf_a "
                                + "ORDER BY id",
                        5, "window");
                expectRows(st,
                        "WITH g AS (SELECT grp, count(*) c FROM surf_a GROUP BY grp) "
                                + "SELECT count(*) FROM g",
                        1, "CTE");
                expectRows(st,
                        "SELECT id FROM surf_a WHERE v IN (SELECT max(v) FROM surf_a) ORDER BY id",
                        1, "subquery IN");
                expectRows(st,
                        "SELECT id FROM surf_a WHERE id = 1 UNION SELECT id FROM surf_a WHERE id = 2",
                        2, "UNION");
                expectRows(st,
                        "SELECT surf_a.grp, surf_b.tag FROM surf_a JOIN surf_b "
                                + "ON surf_a.id = surf_b.a_id ORDER BY surf_b.id",
                        3, "INNER JOIN");
                expectRows(st,
                        "SELECT surf_a.grp, surf_b.tag FROM surf_a LEFT JOIN surf_b "
                                + "ON surf_a.id = surf_b.a_id ORDER BY surf_a.id, surf_b.id",
                        6, "LEFT JOIN");
                expectRows(st,
                        "SELECT surf_a.grp, surf_b.tag FROM surf_a RIGHT JOIN surf_b "
                                + "ON surf_a.id = surf_b.a_id ORDER BY surf_b.id",
                        3, "RIGHT JOIN");
                expectRows(st,
                        "SELECT surf_a.grp, surf_b.tag FROM surf_a FULL JOIN surf_b "
                                + "ON surf_a.id = surf_b.a_id ORDER BY surf_a.id, surf_b.id",
                        6, "FULL JOIN");
                expectRows(st,
                        "SELECT surf_a.id, surf_b.id FROM surf_a CROSS JOIN surf_b", 15,
                        "CROSS JOIN");
                expectRows(st,
                        "SELECT surf_a.id, l.tag FROM surf_a JOIN LATERAL "
                                + "(SELECT tag FROM surf_b WHERE surf_b.a_id = surf_a.id LIMIT 1) l "
                                + "ON true ORDER BY surf_a.id",
                        2, "LATERAL");
            }
        }
    }

    /** Runs a query and asserts the number of rows the server streamed back over the wire. */
    private static void expectRows(Statement st, String sql, int want, String what)
            throws SQLException {
        int got = 0;
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                got++;
            }
        }
        expect(got == want, what + " returns " + want + " rows (got " + got + ")");
    }

    private static void scramOk(NusaTestServer s) throws SQLException {
        try (Connection c = DriverManager.getConnection(s.url());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1")) {
            expect(rs.next(), "authenticated query returns a row");
        }
    }

    private static void scramWrong(NusaTestServer s) {
        boolean threw = false;
        try (Connection ignored = DriverManager.getConnection(s.url("wrong"))) {
            // should not reach here
        } catch (SQLException e) {
            threw = true;
        }
        expect(threw, "wrong password rejected");
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("expectation failed: " + what);
        }
    }
}
