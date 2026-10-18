package com.nusadb.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC {@link DatabaseMetaData} for NusaDB. The introspection methods a tool such as
 * DBeaver uses to build its schema tree ({@link #getCatalogs()}, {@link #getSchemas()},
 * {@link #getTableTypes()}, {@link #getTables}, {@link #getColumns}, {@link #getTypeInfo()}) are
 * answered from the server's {@code information_schema} views. Primary keys
 * ({@link #getPrimaryKeys}) and indexes ({@link #getIndexInfo}) are likewise answered from the
 * {@code information_schema.table_constraints} / {@code key_column_usage} / {@code statistics}
 * views. Relational metadata NusaDB does not model yet (foreign keys, procedures)
 * returns an empty result of the JDBC-specified shape, which tools tolerate. Capability flags
 * report NusaDB's single-node SQL surface.
 */
final class NusaDatabaseMetaData implements DatabaseMetaData {
    private final NusaConnection connection;

    NusaDatabaseMetaData(NusaConnection connection) {
        this.connection = connection;
    }

    // --- ResultSet construction helpers ------------------------------------

    private static byte[] utf8(String s) {
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    /** Build an in-memory result set with the given column names and string rows. */
    private ResultSet rows(String[] columns, List<String[]> data) throws SQLException {
        List<String> cols = new ArrayList<>(columns.length);
        for (String c : columns) {
            cols.add(c);
        }
        List<List<byte[]>> out = new ArrayList<>(data.size());
        for (String[] row : data) {
            List<byte[]> r = new ArrayList<>(row.length);
            for (String cell : row) {
                r.add(utf8(cell));
            }
            out.add(r);
        }
        // The metadata result sets report their columns as text (no per-column type tags).
        List<String> colTypes = java.util.Arrays.asList(new String[cols.size()]);
        return new NusaResultSet(
                new NusaStatement(connection), new Wire.Result(cols, colTypes, out, null));
    }

    private ResultSet empty(String[] columns) throws SQLException {
        return rows(columns, new ArrayList<>());
    }

    /** Run an introspection query and return its collected rows as strings (NULL → null). */
    private List<String[]> query(String sql) throws SQLException {
        Wire.Result result = connection.wire.simpleQuery(sql);
        List<String[]> out = new ArrayList<>(result.rows.size());
        for (List<byte[]> row : result.rows) {
            String[] r = new String[row.size()];
            for (int i = 0; i < row.size(); i++) {
                byte[] cell = row.get(i);
                r[i] = cell == null ? null : new String(cell, StandardCharsets.UTF_8);
            }
            out.add(r);
        }
        return out;
    }

    /** Map a NusaDB type name (from {@code information_schema}) to a {@link java.sql.Types} code. */
    private static int sqlType(String typeName) {
        if (typeName == null) {
            return Types.OTHER;
        }
        String t = typeName.trim().toUpperCase();
        if (t.startsWith("INT") || t.equals("BIGINT") || t.equals("SMALLINT")) {
            return Types.BIGINT;
        }
        if (t.startsWith("BOOL")) {
            return Types.BOOLEAN;
        }
        if (t.startsWith("FLOAT") || t.equals("REAL") || t.startsWith("DOUBLE")) {
            return Types.DOUBLE;
        }
        if (t.startsWith("NUMERIC") || t.startsWith("DECIMAL")) {
            return Types.NUMERIC;
        }
        if (t.startsWith("TIMESTAMP")) {
            return Types.TIMESTAMP;
        }
        if (t.startsWith("TIME")) {
            return Types.TIME;
        }
        if (t.startsWith("DATE")) {
            return Types.DATE;
        }
        if (t.startsWith("TEXT") || t.startsWith("VARCHAR") || t.startsWith("CHAR")) {
            return Types.VARCHAR;
        }
        if (t.startsWith("BYTE") || t.startsWith("VARBINARY") || t.startsWith("BLOB")) {
            return Types.VARBINARY;
        }
        return Types.OTHER;
    }

    /**
     * A representative {@code COLUMN_SIZE}/{@code PRECISION} for a type: max digits for numbers,
     * a canonical text width for temporals, {@code 0} (unknown/unbounded) for text and others.
     */
    private static int columnSize(String typeName) {
        switch (sqlType(typeName)) {
            case Types.BIGINT:
                return 19;
            case Types.BOOLEAN:
                return 1;
            case Types.DOUBLE:
                return 15;
            case Types.NUMERIC:
                return 38;
            case Types.DATE:
                return 10; // YYYY-MM-DD
            case Types.TIME:
                return 8; // HH:MM:SS
            case Types.TIMESTAMP:
                return 26; // YYYY-MM-DD HH:MM:SS.ffffff
            default:
                return 0; // text / binary / other: unbounded or unknown
        }
    }

    // --- Identity / product info -------------------------------------------

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public String getDatabaseProductName() {
        return "NusaDB";
    }

    @Override
    public String getDatabaseProductVersion() {
        return "1.0";
    }

    @Override
    public int getDatabaseMajorVersion() {
        return 1;
    }

    @Override
    public int getDatabaseMinorVersion() {
        return 0;
    }

    @Override
    public String getDriverName() {
        return "nusadb-jdbc";
    }

    @Override
    public String getDriverVersion() {
        return "0.1.0";
    }

    @Override
    public int getDriverMajorVersion() {
        return 0;
    }

    @Override
    public int getDriverMinorVersion() {
        return 1;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 2;
    }

    @Override
    public String getURL() {
        return null;
    }

    @Override
    public String getUserName() {
        return null;
    }

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public String getCatalogSeparator() {
        return ".";
    }

    @Override
    public String getCatalogTerm() {
        return "database";
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public String getSQLKeywords() {
        return "";
    }

    @Override
    public String getNumericFunctions() {
        return "";
    }

    @Override
    public String getStringFunctions() {
        return "";
    }

    @Override
    public String getSystemFunctions() {
        return "";
    }

    @Override
    public String getTimeDateFunctions() {
        return "";
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public int getSQLStateType() {
        return DatabaseMetaData.sqlStateSQL;
    }

    // --- Introspection result sets (information_schema) --------------------

    @Override
    public ResultSet getCatalogs() throws SQLException {
        // A catalog is a whole database, so list the entire cluster via the nusadb_databases system
        // catalog rather than information_schema.schemata (which the engine scopes to the *connected*
        // database, so it would only ever surface one catalog). This lets a schema-tree UI show every
        // database as a sibling node, matching how getCatalogs() is expected to behave. The
        // nusadb_databases catalog exposes a single "name" column. Caveat: NusaDB is engine-per-database,
        // so a connection can only query its own database; selecting another catalog needs a new
        // connection.
        String[] cols = {"TABLE_CAT"};
        List<String[]> out = new ArrayList<>();
        for (String[] r : query("SELECT name FROM nusadb_databases ORDER BY name")) {
            out.add(new String[] {r[0]});
        }
        return rows(cols, out);
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        String[] cols = {"TABLE_SCHEM", "TABLE_CATALOG"};
        List<String[]> out = new ArrayList<>();
        for (String[] r : query(
                "SELECT schema_name, catalog_name FROM information_schema.schemata ORDER BY schema_name")) {
            out.add(new String[] {r[0], r[1]});
        }
        return rows(cols, out);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return getSchemas();
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        String[] cols = {"TABLE_TYPE"};
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"TABLE"});
        out.add(new String[] {"VIEW"});
        return rows(cols, out);
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
            String[] types) throws SQLException {
        String[] cols = {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS", "TYPE_CAT",
            "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION",
        };
        List<String[]> out = new ArrayList<>();
        for (String[] r : query("SELECT table_catalog, table_schema, table_name, table_type "
                + "FROM information_schema.tables ORDER BY table_name")) {
            String type = r[3] == null ? "TABLE" : r[3];
            if (tableNamePattern != null && !likeMatches(tableNamePattern, r[2])) {
                continue;
            }
            out.add(new String[] {r[0], r[1], r[2], type, null, null, null, null, null, null});
        }
        return rows(cols, out);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        String[] cols = {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
            "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE",
            "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
            "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE",
            "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN",
        };
        List<String[]> out = new ArrayList<>();
        for (String[] r : query("SELECT table_catalog, table_schema, table_name, column_name, "
                + "ordinal_position, data_type, is_nullable, character_maximum_length, "
                + "numeric_precision, numeric_scale, column_default FROM information_schema.columns "
                + "ORDER BY table_name, ordinal_position")) {
            String tableName = r[2];
            String columnName = r[3];
            if (tableNamePattern != null && !likeMatches(tableNamePattern, tableName)) {
                continue;
            }
            if (columnNamePattern != null && !likeMatches(columnNamePattern, columnName)) {
                continue;
            }
            String typeName = r[5];
            boolean nullable = !"NO".equalsIgnoreCase(r[6]);
            // COLUMN_SIZE / DECIMAL_DIGITS from the reflected length & precision: the declared
            // VARCHAR(n)/CHAR(n) length, or NUMERIC(p,s) precision, else a per-type default.
            Integer charLen = parseIntOrNull(r[7]);
            Integer numPrecision = parseIntOrNull(r[8]);
            Integer numScale = parseIntOrNull(r[9]);
            int columnSize =
                    charLen != null ? charLen : numPrecision != null ? numPrecision : columnSize(typeName);
            String decimalDigits = numScale == null ? null : Integer.toString(numScale);
            // COLUMN_DEF is the column's DEFAULT SQL; a SERIAL/IDENTITY column's default is
            // nextval('<seq>'), which (as mainstream JDBC drivers do) marks it auto-increment.
            String columnDef = r[10];
            String autoIncrement = columnDef != null && columnDef.startsWith("nextval(") ? "YES" : "NO";
            out.add(new String[] {
                r[0], r[1], tableName, columnName, Integer.toString(sqlType(typeName)), typeName,
                Integer.toString(columnSize), null, decimalDigits, "10",
                Integer.toString(nullable ? columnNullable : columnNoNulls),
                null, columnDef, null, null, null, r[4], nullable ? "YES" : "NO", null, null, null, null,
                autoIncrement, "NO",
            });
        }
        return rows(cols, out);
    }

    /** Parse an optional integer field (a {@code NULL} catalog cell stays {@code null}). */
    private static Integer parseIntOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        String[] cols = {
            "TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX",
            "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE",
            "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE",
            "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX",
        };
        String[][] specs = {
            {"BOOLEAN", Integer.toString(Types.BOOLEAN)},
            {"INT", Integer.toString(Types.BIGINT)},
            {"FLOAT", Integer.toString(Types.DOUBLE)},
            {"NUMERIC", Integer.toString(Types.NUMERIC)},
            {"TEXT", Integer.toString(Types.VARCHAR)},
            {"DATE", Integer.toString(Types.DATE)},
            {"TIME", Integer.toString(Types.TIME)},
            {"TIMESTAMP", Integer.toString(Types.TIMESTAMP)},
            {"UUID", Integer.toString(Types.OTHER)},
            {"JSON", Integer.toString(Types.OTHER)},
        };
        List<String[]> out = new ArrayList<>();
        for (String[] spec : specs) {
            out.add(new String[] {
                spec[0], spec[1], Integer.toString(columnSize(spec[0])), null, null, null,
                Integer.toString(typeNullable), "true", Integer.toString(typeSearchable), "false",
                "false", "false", null, "0", "0", null, null, "10",
            });
        }
        return rows(cols, out);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        String[] cols = {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME",
        };
        // Collect the PRIMARY KEY constraint names (per table) from table_constraints, then expand
        // their member columns from key_column_usage. Keyed by table+constraint so two tables that
        // reuse a constraint name do not bleed into each other.
        java.util.Set<String> pkConstraints = new java.util.HashSet<>();
        for (String[] r : query("SELECT table_name, constraint_name, constraint_type "
                + "FROM information_schema.table_constraints")) {
            if (!"PRIMARY KEY".equalsIgnoreCase(r[2])) {
                continue;
            }
            if (table != null && !table.equals(r[0])) {
                continue;
            }
            pkConstraints.add(r[0] + "\u0000" + r[1]);
        }
        List<String[]> out = new ArrayList<>();
        // JDBC orders getPrimaryKeys rows by COLUMN_NAME (KEY_SEQ still carries the key position).
        for (String[] r : query("SELECT table_catalog, table_schema, table_name, constraint_name, "
                + "column_name, ordinal_position FROM information_schema.key_column_usage "
                + "ORDER BY column_name")) {
            if (table != null && !table.equals(r[2])) {
                continue;
            }
            if (!pkConstraints.contains(r[2] + "\u0000" + r[3])) {
                continue;
            }
            // KEY_SEQ = ordinal_position, PK_NAME = constraint_name.
            out.add(new String[] {r[0], r[1], r[2], r[4], r[5], r[3]});
        }
        return rows(cols, out);
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return foreignKeys();
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return foreignKeys();
    }

    @Override
    public ResultSet getCrossReference(String pc, String ps, String pt, String fc, String fs,
            String ft) throws SQLException {
        return foreignKeys();
    }

    private ResultSet foreignKeys() throws SQLException {
        return empty(new String[] {
            "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_CAT",
            "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE",
            "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY",
        });
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique,
            boolean approximate) throws SQLException {
        String[] cols = {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME",
            "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES",
            "FILTER_CONDITION",
        };
        List<String[]> out = new ArrayList<>();
        // JDBC orders getIndexInfo rows by NON_UNIQUE, TYPE, INDEX_NAME, ORDINAL_POSITION. TYPE is a
        // constant here (tableIndexOther), so non_unique, index_name, seq_in_index suffices.
        for (String[] r : query("SELECT table_catalog, table_schema, table_name, non_unique, "
                + "index_name, seq_in_index, column_name FROM information_schema.statistics "
                + "ORDER BY non_unique, index_name, seq_in_index")) {
            if (table != null && !table.equals(r[2])) {
                continue;
            }
            boolean nonUnique = r[3] != null && !"0".equals(r[3].trim());
            // When the caller asks for unique indexes only, drop the non-unique ones.
            if (unique && nonUnique) {
                continue;
            }
            out.add(new String[] {
                r[0], r[1], r[2], nonUnique ? "true" : "false", null, r[4],
                Integer.toString(tableIndexOther), r[5], r[6], "A", null, null, null,
            });
        }
        return rows(cols, out);
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        return empty(new String[] {
            "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED1", "RESERVED2",
            "RESERVED3", "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME",
        });
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern,
            String procedureNamePattern, String columnNamePattern) throws SQLException {
        return empty(new String[] {
            "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME", "COLUMN_TYPE",
            "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS",
        });
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        return empty(new String[] {
            "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE",
            "SPECIFIC_NAME",
        });
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern,
            String functionNamePattern, String columnNamePattern) throws SQLException {
        return empty(new String[] {
            "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE",
            "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS",
            "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME",
        });
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope,
            boolean nullable) throws SQLException {
        return empty(new String[] {
            "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
            "DECIMAL_DIGITS", "PSEUDO_COLUMN",
        });
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table)
            throws SQLException {
        return getBestRowIdentifier(catalog, schema, table, 0, false);
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table,
            String columnNamePattern) throws SQLException {
        return empty(new String[] {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE",
            "PRIVILEGE", "IS_GRANTABLE",
        });
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return empty(new String[] {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE",
            "IS_GRANTABLE",
        });
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern,
            int[] types) throws SQLException {
        return empty(new String[] {
            "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE",
        });
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
            throws SQLException {
        return empty(new String[] {
            "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM",
            "SUPERTYPE_NAME",
        });
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return empty(new String[] {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME"});
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
            String attributeNamePattern) throws SQLException {
        return empty(new String[] {
            "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE", "ATTR_TYPE_NAME",
            "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF",
            "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
            "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
        });
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return empty(new String[] {"NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION"});
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        return empty(new String[] {
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "COLUMN_SIZE",
            "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH",
            "IS_NULLABLE",
        });
    }

    /**
     * A minimal SQL `LIKE` matcher for the metadata name patterns: `%` (any run) and `_` (any one)
     * wildcards, with `\` as the search-string escape ({@link #getSearchStringEscape()}) so `\_`,
     * `\%`, and `\\` match a literal `_`, `%`, and `\`.
     */
    private static boolean likeMatches(String pattern, String value) {
        if (pattern == null || "%".equals(pattern)) {
            return true;
        }
        if (value == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                // Escaped: the next character is a literal, not a wildcard.
                regex.append(java.util.regex.Pattern.quote(String.valueOf(pattern.charAt(i + 1))));
                i++;
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return value.matches(regex.toString());
    }

    // --- Capability flags ---------------------------------------------------

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean supportsTransactions() {
        return true;
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return true;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return true;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return true;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return true;
    }

    @Override
    public boolean supportsUnion() {
        return true;
    }

    @Override
    public boolean supportsUnionAll() {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return true;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return true;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return true;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return true;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    // --- Limits (0 = no limit / unknown) -----------------------------------

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 0;
    }

    @Override
    public int getMaxConnections() {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public int getMaxStatementLength() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    // --- Wrapper ------------------------------------------------------------

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("nusadb: not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
