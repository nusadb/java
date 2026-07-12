package com.nusadb.hibernate;

import java.sql.Types;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.pagination.LimitHandler;

/**
 * Hibernate ORM 5.6 dialect for NusaDB. A thin layer over the {@code nusadb-jdbc} driver: it registers
 * the column types NusaDB accepts in {@code CREATE TABLE} and renders pagination as inline literals
 * (the server requires a constant {@code LIMIT}).
 *
 * <p>NusaDB has neither identity columns nor sequences, so entity identifiers must be assigned by the
 * application (no {@code @GeneratedValue}). Configure it with:
 *
 * <pre>
 * hibernate.dialect = com.nusadb.hibernate.NusaDialect
 * hibernate.connection.driver_class = com.nusadb.jdbc.NusaDriver
 * hibernate.temp.use_jdbc_metadata_defaults = false
 * </pre>
 */
public class NusaDialect extends Dialect {

    public NusaDialect() {
        super();
        registerColumnType(Types.BOOLEAN, "boolean");
        registerColumnType(Types.BIT, "boolean");
        registerColumnType(Types.TINYINT, "smallint");
        registerColumnType(Types.SMALLINT, "smallint");
        registerColumnType(Types.INTEGER, "int");
        registerColumnType(Types.BIGINT, "bigint");
        registerColumnType(Types.REAL, "real");
        registerColumnType(Types.FLOAT, "real");
        registerColumnType(Types.DOUBLE, "double precision");
        registerColumnType(Types.NUMERIC, "numeric($p,$s)");
        registerColumnType(Types.DECIMAL, "numeric($p,$s)");
        registerColumnType(Types.CHAR, "text");
        registerColumnType(Types.VARCHAR, "text");
        registerColumnType(Types.LONGVARCHAR, "text");
        registerColumnType(Types.NCHAR, "text");
        registerColumnType(Types.NVARCHAR, "text");
        registerColumnType(Types.LONGNVARCHAR, "text");
        registerColumnType(Types.CLOB, "text");
        registerColumnType(Types.NCLOB, "text");
        registerColumnType(Types.DATE, "date");
        registerColumnType(Types.TIME, "time");
        registerColumnType(Types.TIMESTAMP, "timestamp");
        registerColumnType(Types.BINARY, "varbinary");
        registerColumnType(Types.VARBINARY, "varbinary");
        registerColumnType(Types.LONGVARBINARY, "varbinary");
        registerColumnType(Types.BLOB, "varbinary");
    }

    @Override
    public LimitHandler getLimitHandler() {
        return NusaLimitHandler.INSTANCE;
    }

    @Override
    public boolean supportsSequences() {
        // NusaDB has no sequences: identifiers are application-assigned.
        return false;
    }
}
