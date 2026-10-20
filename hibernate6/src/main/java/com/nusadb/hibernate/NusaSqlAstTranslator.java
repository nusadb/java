package com.nusadb.hibernate;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.EntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslator;
import org.hibernate.sql.ast.tree.AbstractUpdateOrDeleteStatement;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.StandardTableGroup;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.predicate.ExistsPredicate;
import org.hibernate.sql.ast.tree.predicate.InSubQueryPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.select.QueryPart;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.results.internal.SqlSelectionImpl;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.sql.model.internal.TableInsertStandard;

/**
 * Renders an UPDATE or DELETE whose restriction joins other tables (an implicit join such as
 * {@code where p.category.name = ?}) as {@code id in (select t.id from t t join .. where ..)}.
 * Hibernate's own emulation is a correlated {@code exists (select 1 .. where c.id = t.category_id)},
 * and the server does not evaluate a correlated subquery in an UPDATE or DELETE yet. Inside the
 * subquery the target table is aliased with its own name, so the references Hibernate qualifies
 * with the table name resolve to that copy and the subquery stands on its own.
 *
 * <p>Renders pagination as a constant {@code LIMIT}/{@code OFFSET} with inlined literal values. NusaDB
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
    protected Predicate determineWhereClauseRestrictionWithJoinEmulation(AbstractUpdateOrDeleteStatement statement) {
        Predicate emulated = super.determineWhereClauseRestrictionWithJoinEmulation(statement);
        Predicate uncorrelated = emulated instanceof ExistsPredicate ? asIdSubquery(statement, (ExistsPredicate) emulated) : null;
        return uncorrelated != null ? uncorrelated : emulated;
    }

    /** {@code id in (select t.id from t t ..)} for the emulated exists, or null to keep it. */
    private Predicate asIdSubquery(AbstractUpdateOrDeleteStatement statement, ExistsPredicate exists) {
        NamedTableReference target = statement.getTargetTable();
        String table = target.getTableExpression();
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*") || !(exists.getExpression().getQueryPart() instanceof QuerySpec)) {
            return null;
        }
        TableGroup root = null;
        for (TableGroup candidate : statement.getFromClause().getRoots()) {
            if (candidate.getPrimaryTableReference() == target) {
                root = candidate;
            }
        }
        if (root == null || !(root.getModelPart() instanceof EntityMappingType)) {
            return null;
        }
        EntityIdentifierMapping id = ((EntityMappingType) root.getModelPart()).getIdentifierMapping();
        List<SelectableMapping> idColumns = new ArrayList<>();
        id.forEachSelectable((i, column) -> idColumns.add(column));
        if (idColumns.size() != 1) {
            return null; // the server has no row-value IN
        }
        QuerySpec subquery = (QuerySpec) exists.getExpression().getQueryPart();
        StandardTableGroup copy = new StandardTableGroup(true, new NavigablePath(table), null, null,
                new NamedTableReference(table, table), null, getSessionFactory());
        List<TableGroup> roots = subquery.getFromClause().getRoots();
        TableGroup dual = roots.isEmpty() ? null : roots.get(0);
        if (dual != null && "d_".equals(dual.getPrimaryTableReference().getIdentificationVariable())) {
            // Hibernate 6.6 hangs the joins on a one-row placeholder table; move them to the copy.
            copy.getTableReferenceJoins().addAll(dual.getTableReferenceJoins());
            for (TableGroupJoin join : dual.getTableGroupJoins()) {
                copy.addTableGroupJoin(join);
            }
            for (TableGroupJoin join : dual.getNestedTableGroupJoins()) {
                copy.addNestedTableGroupJoin(join);
            }
            roots.set(0, copy);
        } else {
            roots.add(0, copy);
        }
        ColumnReference idColumn = new ColumnReference(target.getIdentificationVariable(), idColumns.get(0));
        subquery.getSelectClause().getSqlSelections().clear();
        subquery.getSelectClause().addSqlSelection(new SqlSelectionImpl(idColumn));
        return new InSubQueryPredicate(idColumn, subquery, false);
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
