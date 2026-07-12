package com.nusadb.hibernate;

import org.hibernate.dialect.pagination.AbstractLimitHandler;
import org.hibernate.engine.spi.RowSelection;

/**
 * Renders {@code LIMIT}/{@code OFFSET} as inline literals. NusaDB requires a constant {@code LIMIT}
 * (it does not bind the row count as a parameter), so {@link #supportsVariableLimit()} is {@code false}
 * and the values are written straight into the SQL.
 */
final class NusaLimitHandler extends AbstractLimitHandler {

    static final NusaLimitHandler INSTANCE = new NusaLimitHandler();

    private NusaLimitHandler() {}

    @Override
    public boolean supportsLimit() {
        return true;
    }

    @Override
    public boolean supportsLimitOffset() {
        return true;
    }

    @Override
    public boolean supportsVariableLimit() {
        return false;
    }

    @Override
    public String processSql(String sql, RowSelection selection) {
        if (selection == null) {
            return sql;
        }
        Integer maxRows = selection.getMaxRows();
        Integer firstRow = selection.getFirstRow();
        StringBuilder sb = new StringBuilder(sql);
        if (maxRows != null && maxRows > 0) {
            sb.append(" limit ").append(maxRows.intValue());
        }
        if (firstRow != null && firstRow > 0) {
            sb.append(" offset ").append(firstRow.intValue());
        }
        return sb.toString();
    }
}
