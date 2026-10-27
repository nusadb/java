package com.nusadb.hibernate;

import java.util.List;
import org.hibernate.QueryException;
import org.hibernate.dialect.function.SQLFunction;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.Type;

/**
 * HQL {@code locate(pattern, string[, start])} with the server's {@code position(pattern in string)}:
 * the 1-based position of the first match at or after {@code start}, or 0.
 */
final class NusaLocateFunction implements SQLFunction {

    @Override
    public boolean hasArguments() {
        return true;
    }

    @Override
    public boolean hasParenthesesIfNoArguments() {
        return true;
    }

    @Override
    public Type getReturnType(Type firstArgumentType, Mapping mapping) {
        return StandardBasicTypes.INTEGER;
    }

    @Override
    public String render(Type firstArgumentType, List arguments, SessionFactoryImplementor factory) {
        if (arguments.size() == 2) {
            return "position(" + arguments.get(0) + " in " + arguments.get(1) + ")";
        }
        if (arguments.size() == 3) {
            Object pattern = arguments.get(0);
            Object start = arguments.get(2);
            String found = "position(" + pattern + " in substring(" + arguments.get(1) + " from " + start + "))";
            return "(case when " + found + " = 0 then 0 else " + found + " + " + start + " - 1 end)";
        }
        throw new QueryException("locate() takes 2 or 3 arguments, got " + arguments.size());
    }
}
