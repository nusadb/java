# nusadb-spring-boot-starter: NusaDB for Spring Boot 3

One dependency brings the [`nusadb-jdbc`](../README.md) driver and the
[`nusadb-hibernate6`](../hibernate6/README.md) dialect into a Spring Boot 3 application, and
configures them from the JDBC URL. Built and tested against Spring Boot **3.5** (Hibernate 6.6,
HikariCP 6) and Java 17.

## Usage

```xml
<dependency>
  <groupId>com.nusadb</groupId>
  <artifactId>nusadb-spring-boot-starter</artifactId>
  <version>0.3.0</version>
</dependency>
```

```properties
spring.datasource.url=jdbc:nusadb://host:5678/db
spring.datasource.username=app
spring.datasource.password=secret
```

That is the whole configuration, for JDBC (`JdbcTemplate`) and for JPA
(`spring-boot-starter-data-jpa`) alike.

## What it sets

For a `jdbc:nusadb:` URL, and only where the application leaves the property unset or blank:

| Property | Value | Why |
|---|---|---|
| `spring.datasource.driver-class-name` | `com.nusadb.jdbc.NusaDriver` | Spring Boot picks the driver from a fixed list of databases, and NusaDB is not in it. Without this, startup fails with "Failed to determine a suitable driver class". |
| `spring.datasource.hikari.exception-override-class-name` | `com.nusadb.spring.boot.NusaHikariExceptionOverride` | HikariCP closes a connection on SQLState `0A000` ("feature not supported"). NusaDB reports a SQL construct it does not support yet with that code, as a plain statement error, so the override keeps the connection in the pool. Every other error follows HikariCP's own rules. |

The Hibernate dialect needs no property: `nusadb-hibernate6` registers a dialect resolver, and
Hibernate picks `NusaDialect` from the connection. `spring.jpa.database-platform` may still be set.

## Without the starter

Add `nusadb-jdbc` and `nusadb-hibernate6` and set the two properties above yourself.

## Build & test

```bash
mvn verify -pl spring-boot-starter -am        # unit tests
mvn verify -Pit -pl spring-boot-starter -am   # + StarterIT against a server (see the root README)
```

## License

Apache-2.0
