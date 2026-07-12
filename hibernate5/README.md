# nusadb-hibernate5 — Hibernate ORM 5.x dialect for NusaDB

A thin [Hibernate ORM](https://hibernate.org/) 5.x dialect layered on the
[`nusadb-jdbc`](../README.md) driver. Built and tested against Hibernate **5.6.14.Final**
and Java 17. For Hibernate 6.x use [`../hibernate6`](../hibernate6) instead.

## Usage

```properties
hibernate.dialect                         = com.nusadb.hibernate.NusaDialect
hibernate.connection.driver_class         = com.nusadb.jdbc.NusaDriver
hibernate.connection.url                  = jdbc:nusadb://user@host:5678/nusa
hibernate.temp.use_jdbc_metadata_defaults = false
```

The dialect registers the column types NusaDB accepts (`int`, `bigint`, `smallint`,
`boolean`, `real`, `double precision`, `numeric(p,s)`, `text`, `date`, `time`,
`timestamp`, `varbinary`) and renders pagination (`setMaxResults` / `setFirstResult`)
as a constant `LIMIT`/`OFFSET` via a `LimitHandler` that inlines the values.

### Identifiers

NusaDB has no auto-increment columns or sequences, so entity identifiers must be
**application-assigned** — declare `@Id` without `@GeneratedValue` (entities use the
`javax.persistence` annotations of Hibernate 5):

```java
@Entity
@Table(name = "account")
public class Account {
    @Id
    private Long id;          // assigned by the application
    private String name;
    private int balance;
    // ...
}
```

### Bootstrap

Set `hibernate.temp.use_jdbc_metadata_defaults=false` so Hibernate configures itself
from this dialect rather than `DatabaseMetaData`, which the driver does not yet implement.

## Build & test

```bash
cargo build -p nusadb-server          # the test boots a real server
cd drivers/java && mvn -q -Dmaven.test.skip=true install   # install nusadb-jdbc locally
cd hibernate5
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=cp.txt
java -cp "target/classes;target/test-classes;$(cat cp.txt)" com.nusadb.hibernate.HibernateTest
```

(On a POSIX shell use `:` as the classpath separator.) `HibernateTest` boots a real
`nusadb-server` on an ephemeral port and exercises schema export, persist/load, update,
a query with a row limit, delete, and transaction rollback.

## License

Apache-2.0.
