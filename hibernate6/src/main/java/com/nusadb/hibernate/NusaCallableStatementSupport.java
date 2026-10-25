package com.nusadb.hibernate;

import java.util.List;
import org.hibernate.procedure.internal.AbstractStandardCallableStatementSupport;
import org.hibernate.procedure.spi.FunctionReturnImplementor;
import org.hibernate.procedure.spi.ProcedureCallImplementor;
import org.hibernate.procedure.spi.ProcedureParameterImplementor;
import org.hibernate.sql.exec.internal.JdbcCallImpl;
import org.hibernate.sql.exec.spi.JdbcOperationQueryCall;
import org.hibernate.type.SqlTypes;

/**
 * Renders a stored routine call in the forms the server runs: a procedure as {@code call p(?, ?)},
 * a function as {@code select f(?, ?)}, and a function returning a cursor as
 * {@code select * from f(?, ?)}. Hibernate knows which of the two a call is, so no JDBC escape
 * ({@code {call ..}}) is left for the driver to guess about. A parameter registered by name is
 * written as {@code name => ?}.
 */
final class NusaCallableStatementSupport extends AbstractStandardCallableStatementSupport {

    static final NusaCallableStatementSupport INSTANCE = new NusaCallableStatementSupport();

    private NusaCallableStatementSupport() {}

    @Override
    public JdbcOperationQueryCall interpretCall(ProcedureCallImplementor<?> procedureCall) {
        FunctionReturnImplementor<?> functionReturn = procedureCall.getFunctionReturn();
        String start;
        if (functionReturn == null) {
            start = "call ";
        } else if (functionReturn.getJdbcTypeCode() == SqlTypes.REF_CURSOR) {
            start = "select * from ";
        } else {
            start = "select ";
        }

        List<? extends ProcedureParameterImplementor<?>> parameters =
                procedureCall.getParameterMetadata().getRegistrationsAsList();
        JdbcCallImpl.Builder builder = new JdbcCallImpl.Builder();
        StringBuilder sql = new StringBuilder(start).append(procedureCall.getProcedureName()).append('(');
        for (int i = 0; i < parameters.size(); i++) {
            ProcedureParameterImplementor<?> parameter = parameters.get(i);
            if (i > 0) {
                sql.append(',');
            }
            if (parameter.getName() != null) {
                sql.append(parameter.getName()).append(" => ");
            }
            sql.append('?');
            builder.addParameterRegistration(parameter.toJdbcParameterRegistration(i + 1, procedureCall));
        }
        builder.setCallableName(sql.append(')').toString());
        return builder.buildJdbcCall();
    }
}
