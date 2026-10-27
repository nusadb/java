package com.nusadb.hibernate;

import java.util.List;
import org.hibernate.QueryException;
import org.hibernate.dialect.function.SQLFunction;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.Type;

/**
 * HQL {@code trim([[both|leading|trailing] [char] from] string)}. The server needs the character to
 * trim spelled out, so {@code trim(x)} becomes {@code trim(both ' ' from x)}.
 */
final class NusaTrimFunction implements SQLFunction {

    @Override
    public boolean hasArguments() {
        return true;
    }

    @Override
    public boolean hasParenthesesIfNoArguments() {
        return false;
    }

    @Override
    public Type getReturnType(Type firstArgumentType, Mapping mapping) {
        return StandardBasicTypes.STRING;
    }

    @Override
    public String render(Type firstArgumentType, List arguments, SessionFactoryImplementor factory) {
        if (arguments.isEmpty()) {
            throw new QueryException("trim() needs an argument");
        }
        // HQL passes the words as separate arguments: [spec] [char] [from] string.
        String spec = "both";
        String character = "' '";
        int i = 0;
        String first = arguments.get(0).toString();
        if (first.equalsIgnoreCase("both") || first.equalsIgnoreCase("leading") || first.equalsIgnoreCase("trailing")) {
            spec = first.toLowerCase(java.util.Locale.ROOT);
            i++;
        }
        if (i < arguments.size() - 1 && !arguments.get(i).toString().equalsIgnoreCase("from")) {
            character = arguments.get(i).toString();
            i++;
        }
        if (i < arguments.size() - 1 && arguments.get(i).toString().equalsIgnoreCase("from")) {
            i++;
        }
        if (i != arguments.size() - 1) {
            throw new QueryException("trim(): could not read the arguments " + arguments);
        }
        return "trim(" + spec + " " + character + " from " + arguments.get(i) + ")";
    }
}
