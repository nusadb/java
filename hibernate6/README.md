# nusadb-hibernate6: Hibernate ORM 6.x dialect for NusaDB

A thin [Hibernate ORM](https://hibernate.org/) 6.x dialect layered on the
[`nusadb-jdbc`](../README.md) driver. Built and tested against Hibernate **6.4.4.Final**
and Java 17.

## Usage

```properties
hibernate.dialect                        = com.nusadb.hibernate.NusaDialect
hibernate.connection.driver_class        = com.nusadb.jdbc.NusaDriver
hibernate.connection.url                 = jdbc:nusadb://user@host:5678/nusa
hibernate.boot.allow_jdbc_metadata_access = false
```

The dialect maps JDBC types to the column types NusaDB accepts (`int`, `bigint`,
`smallint`, `boolean`, `real`, `double precision`, `numeric(p,s)`, `text`, `date`,
`time`, `timestamp`, `varbinary`) and renders pagination (`setMaxResults` /
`setFirstResult`) as a constant `LIMIT`/`OFFSET` with inlined literals.

Booleans are rendered as `true`/`false` (so `findByActiveTrue` and `cb.isTrue` work), and
`java.util.UUID` maps to the native `uuid` column type.

### Identifiers

The NusaDB server supports identity columns (`SERIAL`, `GENERATED ... AS IDENTITY`) and sequences,
but this dialect does not enable Hibernate's `IDENTITY` or `SEQUENCE` generation yet. Until it
does, entity identifiers must be **application-assigned**: declare `@Id` without
`@GeneratedValue` (entities use the `jakarta.persistence` annotations of Hibernate 6):

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

Set `hibernate.boot.allow_jdbc_metadata_access=false` so Hibernate
configures itself from this dialect. The driver implements `DatabaseMetaData`, but booting
Hibernate from it has not been validated with this dialect yet.

## Build & test

From the repository root (see the [root README](../README.md#build--test) for how the
integration tests find a server):

```bash
mvn verify -Pit -pl hibernate6 -am
```

`HibernateIT` exercises schema export, persist/load, update, a query with a row limit,
delete, and transaction rollback.

## License

Apache-2.0.
