package com.nusadb.hibernate;

import java.util.Collection;
import java.util.List;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.dialect.function.array.AbstractArrayFillFunction;
import org.hibernate.dialect.function.array.AbstractArrayPositionFunction;
import org.hibernate.dialect.function.array.AbstractArrayPositionsFunction;
import org.hibernate.dialect.function.array.ArrayConcatElementFunction;
import org.hibernate.dialect.function.array.ArrayConcatFunction;
import org.hibernate.dialect.function.array.ArrayConstructorFunction;
import org.hibernate.dialect.function.array.ArrayContainsUnnestFunction;
import org.hibernate.dialect.function.array.ArrayIncludesUnnestFunction;
import org.hibernate.dialect.function.array.ArrayIntersectsUnnestFunction;
import org.hibernate.dialect.function.array.ArrayToStringFunction;
import org.hibernate.dialect.function.array.DdlTypeHelper;
import org.hibernate.metamodel.mapping.JdbcMappingContainer;
import org.hibernate.query.ReturnableType;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.Literal;
import org.hibernate.type.BasicPluralType;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * The HQL array functions ({@code array(..)}, {@code array_position}, {@code array_append}, ...)
 * rendered as the server's array syntax: {@code array[..]} constructors, the {@code ||},
 * {@code @>} and {@code &&} operators, slices, and the {@code array_*} functions it has.
 */
final class NusaArrayFunctions {

    private NusaArrayFunctions() {}

    static void register(FunctionContributions contributions) {
        SqmFunctionRegistry registry = contributions.getFunctionRegistry();
        TypeConfiguration types = contributions.getTypeConfiguration();
        registry.register("array", new ArrayConstructorFunction(false, true));
        registry.register("array_list", new ArrayConstructorFunction(true, true));
        registry.register("array_position", new Position(types));
        registry.register("array_positions", new Positions(false, types));
        registry.register("array_positions_list", new Positions(true, types));
        registry.register("array_concat", new Concat());
        registry.register("array_prepend", new ConcatElement(true));
        registry.register("array_append", new ConcatElement(false));
        registry.register("array_contains", new Contains(false, types));
        registry.register("array_contains_nullable", new Contains(true, types));
        if (hasHibernate66Functions()) {
            Hibernate66.register(registry, types);
        }
        registry.register("array_fill", new Fill(false));
        registry.register("array_fill_list", new Fill(true));
        registry.register("array_to_string", new ArrayToStringFunction(types));
    }

    /** {@code array_includes} and {@code array_intersects} came with Hibernate 6.6. */
    private static boolean hasHibernate66Functions() {
        try {
            Class.forName("org.hibernate.dialect.function.array.ArrayIntersectsUnnestFunction", false,
                    SqmFunctionRegistry.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Loaded only on Hibernate 6.6 and later, whose classes it extends. */
    private static final class Hibernate66 {
        static void register(SqmFunctionRegistry registry, TypeConfiguration types) {
            registry.register("array_includes", new Includes(false, types));
            registry.register("array_includes_nullable", new Includes(true, types));
            registry.register("array_intersects", new Intersects(false, types));
            registry.register("array_intersects_nullable", new Intersects(true, types));
        }
    }

    /** A null or string literal has no type of its own on the server; other expressions do. */
    private static boolean needsCast(Expression expression) {
        return expression instanceof Literal
                && (((Literal) expression).getLiteralValue() == null
                        || expression.getExpressionType().getSingleJdbcMapping().getJdbcType().isString());
    }

    private static void renderTyped(Expression expression, SqlAppender sql, SqlAstTranslator<?> walker) {
        if (needsCast(expression)) {
            sql.append("cast(");
            walker.render(expression, SqlAstNodeRenderingMode.DEFAULT);
            sql.append(" as ");
            sql.append(DdlTypeHelper.getCastTypeName(expression.getExpressionType(),
                    walker.getSessionFactory().getTypeConfiguration()));
            sql.append(')');
        } else {
            walker.render(expression, SqlAstNodeRenderingMode.DEFAULT);
        }
    }

    private static void render(Expression expression, SqlAstTranslator<?> walker) {
        walker.render(expression, SqlAstNodeRenderingMode.DEFAULT);
    }

    /** The needle is an array (rather than one element), as {@code array_contains(a, array(..))} allows. */
    private static boolean isArray(Expression needle) {
        if (needle instanceof Literal) {
            Object value = ((Literal) needle).getLiteralValue();
            return value != null && (value.getClass().isArray() || value instanceof Collection);
        }
        JdbcMappingContainer type = needle.getExpressionType();
        return type == null || type.getSingleJdbcMapping() instanceof BasicPluralType;
    }

    /**
     * {@code array_contains(a, e)} as {@code a @> array[e]}, and {@code a @> b} for an array of needles.
     * The nullable form also finds a null element.
     */
    static final class Contains extends ArrayContainsUnnestFunction {
        Contains(boolean nullable, TypeConfiguration types) {
            super(nullable, types);
        }

        @Override
        public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> returnType,
                SqlAstTranslator<?> walker) {
            Expression haystack = (Expression) arguments.get(0);
            Expression needle = (Expression) arguments.get(1);
            if (isArray(needle)) {
                if (nullable) {
                    super.render(sql, arguments, returnType, walker);
                    return;
                }
                NusaArrayFunctions.render(haystack, walker);
                sql.append("@>");
                NusaArrayFunctions.render(needle, walker);
            } else if (nullable) {
                sql.append("array_position(");
                NusaArrayFunctions.render(haystack, walker);
                sql.append(',');
                NusaArrayFunctions.render(needle, walker);
                sql.append(") is not null");
            } else {
                NusaArrayFunctions.render(haystack, walker);
                sql.append("@>array[");
                renderTyped(needle, sql, walker);
                sql.append(']');
            }
        }
    }

    /** {@code array_includes(a, b)}: every element of {@code b} is in {@code a}, as {@code a @> b}. */
    static final class Includes extends ArrayIncludesUnnestFunction {
        Includes(boolean nullable, TypeConfiguration types) {
            super(nullable, types);
        }

        @Override
        public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> returnType,
                SqlAstTranslator<?> walker) {
            if (nullable) {
                super.render(sql, arguments, returnType, walker);
                return;
            }
            NusaArrayFunctions.render((Expression) arguments.get(0), walker);
            sql.append("@>");
            NusaArrayFunctions.render((Expression) arguments.get(1), walker);
        }
    }

    /** {@code array_intersects(a, b)}: the arrays share an element, as {@code a && b}. */
    static final class Intersects extends ArrayIntersectsUnnestFunction {
        Intersects(boolean nullable, TypeConfiguration types) {
            super(nullable, types);
        }

        @Override
        public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> returnType,
                SqlAstTranslator<?> walker) {
            if (nullable) {
                super.render(sql, arguments, returnType, walker);
                return;
            }
            NusaArrayFunctions.render((Expression) arguments.get(0), walker);
            sql.append("&&");
            NusaArrayFunctions.render((Expression) arguments.get(1), walker);
        }
    }

    /**
     * {@code array_position(a, e[, from])}: 0 when the element is missing, null for a null array.
     * The start position is a slice, {@code a[from:]}, since the server's function takes two arguments.
     */
    static final class Position extends AbstractArrayPositionFunction {
        Position(TypeConfiguration types) {
            super(types);
        }

        @Override
        public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> returnType,
                SqlAstTranslator<?> walker) {
            Expression array = (Expression) arguments.get(0);
            Expression element = (Expression) arguments.get(1);
            sql.append("case when ");
            NusaArrayFunctions.render(array, walker);
            sql.append(" is not null then coalesce(array_position(");
            if (arguments.size() > 2) {
                Expression from = (Expression) arguments.get(2);
                sql.append('(');
                NusaArrayFunctions.render(array, walker);
                sql.append(")[");
                NusaArrayFunctions.render(from, walker);
                sql.append(":],");
                NusaArrayFunctions.render(element, walker);
                sql.append(")+");
                NusaArrayFunctions.render(from, walker);
                sql.append("-1,0) end");
            } else {
                NusaArrayFunctions.render(array, walker);
                sql.append(',');
                NusaArrayFunctions.render(element, walker);
                sql.append("),0) end");
            }
        }
    }

    /** {@code array_positions(a, e)}: every position of the element, as an array (or a list). */
    static final class Positions extends AbstractArrayPositionsFunction {
        Positions(boolean list, TypeConfiguration types) {
            super(list, types);
        }

        @Override
        public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> returnType,
                SqlAstTranslator<?> walker) {
            sql.append("array_positions(");
            NusaArrayFunctions.render((Expression) arguments.get(0), walker);
            sql.append(',');
            NusaArrayFunctions.render((Expression) arguments.get(1), walker);
            sql.append(')');
        }
    }

    /** {@code array_concat(a, b, ..)} as {@code a||b||..}; null when any array is null. */
    static final class Concat extends ArrayConcatFunction {
        Concat() {
            super("", "||", "");
        }

        @Override
        public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> returnType,
                SqlAstTranslator<?> walker) {
            sql.append("case when ");
            for (int i = 0; i < arguments.size(); i++) {
                if (i > 0) {
                    sql.append(" and ");
                }
                arguments.get(i).accept(walker);
                sql.append(" is not null");
            }
            sql.append(" then ");
            super.render(sql, arguments, returnType, walker);
            sql.append(" end");
        }
    }

    /**
     * {@code array_append(a, e)} and {@code array_prepend(e, a)}: the element goes in as a one-element
     * array, {@code a||array[e]}, so a null element is added rather than turning the result null.
     */
    static final class ConcatElement extends ArrayConcatElementFunction {
        ConcatElement(boolean prepend) {
            super("", "||", "", prepend);
        }

        @Override
        public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> returnType,
                SqlAstTranslator<?> walker) {
            Expression array = (Expression) arguments.get(prepend ? 1 : 0);
            Expression element = (Expression) arguments.get(prepend ? 0 : 1);
            sql.append("case when ");
            NusaArrayFunctions.render(array, walker);
            sql.append(" is not null then ");
            if (prepend) {
                sql.append("array[");
                renderTyped(element, sql, walker);
                sql.append("]||");
                NusaArrayFunctions.render(array, walker);
            } else {
                NusaArrayFunctions.render(array, walker);
                sql.append("||array[");
                renderTyped(element, sql, walker);
                sql.append(']');
            }
            sql.append(" end");
        }
    }

    /** {@code array_fill(e, n)}: an array of {@code n} copies of the element. */
    static final class Fill extends AbstractArrayFillFunction {
        Fill(boolean list) {
            super(list);
        }

        @Override
        public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> returnType,
                SqlAstTranslator<?> walker) {
            sql.append("array_fill(");
            renderTyped((Expression) arguments.get(0), sql, walker);
            sql.append(",array[");
            NusaArrayFunctions.render((Expression) arguments.get(1), walker);
            sql.append("])");
        }
    }
}
