package com.nusadb.hibernate;

import java.sql.Types;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupportImpl;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.sqm.FetchClauseType;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.UUIDJdbcType;
import org.hibernate.type.descriptor.sql.internal.DdlTypeImpl;

/**
 * Hibernate ORM 6.x dialect for NusaDB. A thin layer over the {@code nusadb-jdbc} driver: it maps
 * JDBC type codes to the column types NusaDB accepts in {@code CREATE TABLE} and renders pagination
 * as inline literals (the server requires a constant {@code LIMIT}).
 *
 * <p>NusaDB has neither identity columns nor sequences, so entity identifiers must be assigned by the
 * application (no {@code @GeneratedValue}). Configure it with:
 *
 * <pre>
 * hibernate.dialect = com.nusadb.hibernate.NusaDialect
 * hibernate.connection.driver_class = com.nusadb.jdbc.NusaDriver
 * hibernate.boot.allow_jdbc_metadata_access = false
 * </pre>
 */
public class NusaDialect extends Dialect {

    public NusaDialect() {
        super(DatabaseVersion.make(1, 0));
    }

    @Override
    protected String columnType(int sqlTypeCode) {
        switch (sqlTypeCode) {
            case Types.BOOLEAN:
            case Types.BIT:
                return "boolean";
            case Types.TINYINT:
            case Types.SMALLINT:
                return "smallint";
            case Types.INTEGER:
                return "int";
            case Types.BIGINT:
                return "bigint";
            case Types.REAL:
            case Types.FLOAT:
                return "real";
            case Types.DOUBLE:
                return "double precision";
            case Types.NUMERIC:
            case Types.DECIMAL:
                return "numeric($p,$s)";
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                return "text";
            case Types.DATE:
                return "date";
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return "time";
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return "timestamp";
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return "varbinary";
            default:
                return super.columnType(sqlTypeCode);
        }
    }

    @Override
    protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
        super.registerColumnTypes(typeContributions, serviceRegistry);
        typeContributions.getTypeConfiguration().getDdlTypeRegistry()
                .addDescriptor(new DdlTypeImpl(SqlTypes.UUID, "uuid", this));
    }

    @Override
    public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
        super.contributeTypes(typeContributions, serviceRegistry);
        // java.util.UUID as the native UUID type (bound with setObject), not as 16 raw bytes.
        typeContributions.getTypeConfiguration().getJdbcTypeRegistry().addDescriptor(UUIDJdbcType.INSTANCE);
    }

    @Override
    public int getPreferredSqlTypeCodeForBoolean() {
        return Types.BOOLEAN;
    }

    // NusaDB has a real BOOLEAN: comparing it with 1/0 (the generic rendering) is a type error.

    @Override
    public String toBooleanValueString(boolean bool) {
        return bool ? "true" : "false";
    }

    @Override
    public void appendBooleanValueString(SqlAppender appender, boolean bool) {
        appender.appendSql(toBooleanValueString(bool));
    }

    @Override
    public LimitHandler getLimitHandler() {
        return NusaLimitHandler.INSTANCE;
    }

    @Override
    public boolean supportsFetchClause(FetchClauseType type) {
        // NusaDB has no ANSI 'OFFSET .. FETCH' clause; pagination is rendered as a constant
        // 'LIMIT'/'OFFSET' by NusaSqlAstTranslator (the server rejects a parameterised row count).
        return false;
    }

    @Override
    public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
        return new StandardSqlAstTranslatorFactory() {
            @Override
            protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
                    SessionFactoryImplementor sessionFactory, Statement statement) {
                return new NusaSqlAstTranslator<>(sessionFactory, statement);
            }
        };
    }

    @Override
    public IdentityColumnSupport getIdentityColumnSupport() {
        // NusaDB has no auto-increment columns: report no identity support so Hibernate
        // expects application-assigned identifiers.
        return new IdentityColumnSupportImpl();
    }
}
