package com.nusadb.hibernate;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.hibernate.type.AbstractSingleColumnStandardBasicType;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.java.UUIDTypeDescriptor;
import org.hibernate.type.descriptor.sql.BasicBinder;
import org.hibernate.type.descriptor.sql.BasicExtractor;
import org.hibernate.type.descriptor.sql.SqlTypeDescriptor;

/**
 * {@code java.util.UUID} in a native {@code uuid} column: bound with {@code setObject(UUID)} and
 * read with {@code getObject}, which the driver answers with a {@code UUID}. It replaces Hibernate's
 * default mapping to 16 raw bytes.
 */
final class NusaUuidType extends AbstractSingleColumnStandardBasicType<UUID> {

    static final NusaUuidType INSTANCE = new NusaUuidType();

    private NusaUuidType() {
        super(UuidSqlType.INSTANCE, UUIDTypeDescriptor.INSTANCE);
    }

    @Override
    public String getName() {
        return "nusa-uuid";
    }

    @Override
    protected boolean registerUnderJavaType() {
        return true;
    }

    @Override
    public String[] getRegistrationKeys() {
        return new String[] {getName(), "uuid", UUID.class.getName()};
    }

    /** The column side: {@link Types#OTHER}, which the dialect spells {@code uuid}. */
    static final class UuidSqlType implements SqlTypeDescriptor {
        static final UuidSqlType INSTANCE = new UuidSqlType();

        @Override
        public int getSqlType() {
            return Types.OTHER;
        }

        @Override
        public boolean canBeRemapped() {
            return true;
        }

        @Override
        public <X> ValueBinder<X> getBinder(JavaTypeDescriptor<X> javaTypeDescriptor) {
            return new BasicBinder<X>(javaTypeDescriptor, this) {
                @Override
                protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
                        throws SQLException {
                    st.setObject(index, javaTypeDescriptor.unwrap(value, UUID.class, options));
                }

                @Override
                protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
                        throws SQLException {
                    st.setObject(name, javaTypeDescriptor.unwrap(value, UUID.class, options));
                }
            };
        }

        @Override
        public <X> ValueExtractor<X> getExtractor(JavaTypeDescriptor<X> javaTypeDescriptor) {
            return new BasicExtractor<X>(javaTypeDescriptor, this) {
                @Override
                protected X doExtract(ResultSet rs, String name, WrapperOptions options) throws SQLException {
                    return javaTypeDescriptor.wrap(rs.getObject(name), options);
                }

                @Override
                protected X doExtract(CallableStatement statement, int index, WrapperOptions options)
                        throws SQLException {
                    return javaTypeDescriptor.wrap(statement.getObject(index), options);
                }

                @Override
                protected X doExtract(CallableStatement statement, String name, WrapperOptions options)
                        throws SQLException {
                    return javaTypeDescriptor.wrap(statement.getObject(name), options);
                }
            };
        }
    }
}
