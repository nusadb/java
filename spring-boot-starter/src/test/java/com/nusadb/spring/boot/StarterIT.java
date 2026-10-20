package com.nusadb.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nusadb.jdbc.NusaListener;
import com.nusadb.testing.NusaTestServer;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.SQLExceptionOverride;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** A Spring Boot application configured with nothing but URL, username and password. */
class StarterIT {

    @Configuration
    @EnableAutoConfiguration
    static class App {}

    /** HikariCP's own rules, as without the starter. */
    public static class HikariDefault implements SQLExceptionOverride {}

    private static ConfigurableApplicationContext start(String... extra) {
        NusaTestServer s = NusaTestServer.get();
        String[] args = {
                "--spring.datasource.url=jdbc:nusadb://" + s.host() + ":" + s.port() + "/" + s.database(),
                "--spring.datasource.username=" + s.user(),
                "--spring.datasource.password=" + s.password(),
                "--spring.datasource.hikari.maximum-pool-size=1"};
        String[] all = new String[args.length + extra.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(extra, 0, all, args.length, extra.length);
        return new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE).run(all);
    }

    @Test
    void urlUsernameAndPasswordAreEnough() {
        try (ConfigurableApplicationContext ctx = start("--spring.datasource.driver-class-name=")) {
            assertEquals(1, ctx.getBean(JdbcTemplate.class).queryForObject("SELECT 1", Integer.class));
            HikariDataSource ds = (HikariDataSource) ctx.getBean(DataSource.class);
            assertEquals("com.nusadb.jdbc.NusaDriver", ds.getDriverClassName());
            assertEquals(NusaHikariExceptionOverride.class.getName(), ds.getExceptionOverrideClassName());
        }
    }

    @Test
    void anUnsupportedStatementDoesNotCostThePooledConnection() throws SQLException {
        try (ConfigurableApplicationContext ctx = start()) {
            assertSame(physicalBefore(ctx), physicalAfter(ctx), "the pool kept the same connection");
        }
    }

    @Test
    void withoutTheOverrideHikariReplacesTheConnection() throws SQLException {
        try (ConfigurableApplicationContext ctx = start(
                "--spring.datasource.hikari.exception-override-class-name=" + HikariDefault.class.getName())) {
            assertNotSame(physicalBefore(ctx), physicalAfter(ctx));
        }
    }

    /** The pooled connection's driver object, after running a statement the server answers with 0A000. */
    private static NusaListener physicalBefore(ConfigurableApplicationContext ctx) throws SQLException {
        try (Connection c = ctx.getBean(DataSource.class).getConnection(); Statement st = c.createStatement()) {
            // taken first: once HikariCP evicts the connection, even unwrap fails with "Connection is closed"
            NusaListener physical = c.unwrap(NusaListener.class);
            st.execute("DROP TABLE IF EXISTS starter_item");
            st.execute("DROP TABLE IF EXISTS starter_kind");
            st.execute("CREATE TABLE starter_kind (id INT PRIMARY KEY, name TEXT)");
            st.execute("CREATE TABLE starter_item (id INT PRIMARY KEY, n INT, kind_id INT)");
            st.execute("INSERT INTO starter_kind VALUES (1, 'x')");
            st.execute("INSERT INTO starter_item VALUES (1, 0, 1)");
            // a subquery correlated to another table in UPDATE: the server answers 0A000
            SQLException e = assertThrows(SQLException.class, () -> st.executeUpdate("UPDATE starter_item SET n = 1 WHERE EXISTS "
                    + "(SELECT 1 FROM starter_kind k WHERE k.id = starter_item.kind_id AND k.name = 'x')"));
            assertEquals("0A000", e.getSQLState());
            return physical;
        }
    }

    private static NusaListener physicalAfter(ConfigurableApplicationContext ctx) throws SQLException {
        try (Connection c = ctx.getBean(DataSource.class).getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE starter_item");
            st.execute("DROP TABLE starter_kind");
            return c.unwrap(NusaListener.class);
        }
    }
}
