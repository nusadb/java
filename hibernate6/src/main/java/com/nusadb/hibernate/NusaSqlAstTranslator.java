package com.nusadb.hibernate;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslator;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.select.QueryPart;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.sql.model.internal.TableInsertStandard;

/**
 * Renders pagination as a constant {@code LIMIT}/{@code OFFSET} with inlined literal values. NusaDB
 * rejects a parameterised row count, so Hibernate's default ANSI
 * {@code OFFSET .. FETCH FIRST ? ROWS ONLY} (which binds the count as a JDBC parameter) cannot be used.
 */
final class NusaSqlAstTranslator<T extends JdbcOperation> extends StandardSqlAstTranslator<T> {

    NusaSqlAstTranslator(SessionFactoryImplementor sessionFactory, Statement statement) {
        super(sessionFactory, statement);
    }

    @Override
    protected void renderInsertIntoNoColumns(TableInsertStandard tableInsert) {
        // An entity whose only column is a generated id.
        renderIntoIntoAndTable(tableInsert);
        appendSql("default values");
    }

    @Override
    protected void renderOffsetFetchClause(QueryPart queryPart, boolean renderOffsetRowsKeyword) {
        final Expression offset;
        final Expression fetch;
        if (queryPart.isRoot() && hasLimit()) {
            prepareLimitOffsetParameters();
            offset = getOffsetParameter();
            fetch = getLimitParameter();
        } else {
            offset = queryPart.getOffsetClauseExpression();
            fetch = queryPart.getFetchClauseExpression();
        }
        if (fetch != null) {
            appendSql(" limit ");
            renderExpressionAsLiteral(fetch, getJdbcParameterBindings());
        }
        if (offset != null) {
            appendSql(" offset ");
            renderExpressionAsLiteral(offset, getJdbcParameterBindings());
        }
    }
}
