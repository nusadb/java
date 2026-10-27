# nusadb-jdbc: JDBC driver for NusaDB

A pure-Java JDBC driver that speaks the [Nusa Wire Protocol](https://github.com/nusadb/nusadb/blob/master/docs/wire-protocol.md)
(`PROTOCOL_VERSION 1.1`) directly over a socket. No native code, no third-party
dependencies: SCRAM-SHA-256 uses the JDK's `javax.crypto`. Requires Java 17+.
`ResultSetMetaData.getColumnType` / `getColumnTypeName` report each column's NusaDB type
(protocol 1.1).

## Installation

```xml
<dependency>
  <groupId>com.nusadb</groupId>
  <artifactId>nusadb-jdbc</artifactId>
  <version>0.3.0</version>
</dependency>
```

Using several NusaDB artifacts (for example the driver and a Hibernate dialect)? Import the BOM
once and leave the versions out:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.nusadb</groupId>
      <artifactId>nusadb-bom</artifactId>
      <version>0.3.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.nusadb</groupId>
    <artifactId>nusadb-jdbc</artifactId>
  </dependency>
  <dependency>
    <groupId>com.nusadb</groupId>
    <artifactId>nusadb-hibernate6</artifactId>
  </dependency>
</dependencies>
```

A Spring Boot 3 application needs only `nusadb-spring-boot-starter` (from 0.3.0): it brings the
driver and the Hibernate dialect, and `spring.datasource.url`, `username` and `password` are the
whole configuration. See [spring-boot-starter/README.md](spring-boot-starter/README.md).

## Usage

```java
import java.sql.*;

// The driver registers itself; explicit Class.forName is optional on modern JVMs.
String url = "jdbc:nusadb://nusadb-root@127.0.0.1:5678/nusadb";
try (Connection conn = DriverManager.getConnection(url)) {
    try (Statement st = conn.createStatement()) {
        st.execute("CREATE TABLE t (id INT NOT NULL, name TEXT)");
    }
    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)")) {
        ps.setInt(1, 1);
        ps.setString(2, "alice");
        ps.executeUpdate();
    }
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT id, name FROM t")) {
        while (rs.next()) {
            System.out.println(rs.getInt("id") + " " + rs.getString("name"));
        }
    }
}
```

### URL

```
jdbc:nusadb://[user[:password]@]host[:port]/database
```

`user` / `password` may also be passed via the `Properties` argument to
`DriverManager.getConnection`. Placeholders are `?` (JDBC style) or positional `$1`, `$2`, ….

Options go after the database (`?name=value&...`) or into the `Properties`; names are not case
sensitive.

| Option | Default | Meaning |
|---|---|---|
| `connectTimeout` | `10` | Seconds allowed for the TCP connect, TLS handshake and login together. `0` waits forever. `loginTimeout`, then `DriverManager.setLoginTimeout`, is used when it is set and this is not. |
| `loginTimeout` | `0` | Seconds for the same, when `connectTimeout` is not given (`0` = use `DriverManager`'s login timeout, else 10). |
| `socketTimeout` | `0` | Seconds to wait for any reply. When it runs out the connection is closed (the late reply would leave the stream out of step). Same as `Connection.setNetworkTimeout`. |
| `tcpKeepAlive` | `false` | Turn on TCP keep-alive probes, so a peer that went away is noticed on an idle connection. |
| `currentSchema` | | Schema the session's search path is set to after login; a comma-separated list (`%2C` in a URL) sets several. Unqualified names resolve, and tables are created, in the first. `Connection.getSchema` reports the head of the path and `setSchema` replaces it. |
| `defaultRowFetchSize` | `0` | Fetch size every new statement starts with, so large results are streamed without calling `setFetchSize`. See [Large results](#large-results-setfetchsize). |
| `ApplicationName` | | Name returned by `Connection.getClientInfo("ApplicationName")`, which `setClientInfo` can change. It is kept by the driver: the server has no setting to show it in. |
| `rewriteBatchedInserts` | `false` | Send a batch of a single-row `INSERT ... VALUES (...)` as multi-row INSERTs. See [Batch updates](#batch-updates). |
| `stringType` | `text` | `text`: string parameters are sent as `TEXT`. `unspecified`: the server infers their type from the value, as before 0.2.1. See [Parameter types](#parameter-types). |
| `preparedStatementCacheQueries` | `256` | Server-side statements kept per connection, by SQL text and parameter types, for later `PreparedStatement`s with the same SQL (`0` = none). See [Statement reuse](#statement-reuse). |
| `escapeSyntaxCallMode` | `select` | How `prepareCall` runs `{call f(..)}`: `select` (`SELECT f(..)`), `callIfNoReturn` (`CALL f(..)` unless it is `{? = call ..}`) or `call`. See [Stored functions and procedures](#stored-functions-and-procedures). |
| `targetServerType` | `any` | With several hosts: the role the server must have, `primary`, `secondary`, `preferPrimary` or `preferSecondary`. See [Several hosts](#several-hosts-failover). |
| `loadBalanceHosts` | `false` | Try the hosts in random order instead of URL order. |
| `hostRecheckSeconds` | `10` | How long a host found down, or of the wrong role, is tried after the others. |
| `tls`, `tlsCa`, `tlsDomain`, `tlsInsecure` | off | See [TLS](#tls). |

### Several hosts (failover)

A URL may name several servers, `jdbc:nusadb://db1:5678,db2:5678,[fd00::3]:5678/app`. A connection
goes to the first one that answers; a host that cannot be reached, or that refuses with "too many
connections", is skipped, while an error such as a wrong password is reported at once. When every
host fails the error (SQLState `08001`) names each host and why. A pool keeps working when its
server goes away: the next connections it opens go to the next host.

`targetServerType=primary` skips a read-only standby (a server started with `--standby-from`),
`secondary` skips the primary, and `preferPrimary` / `preferSecondary` take the other kind when none
of the preferred kind is up. The server does not report its role, so the driver tells a standby by
creating a temporary table in a transaction that it rolls back: a standby refuses it. This costs a
few round trips per connection, and only with `targetServerType`. What a connection learns about a
host (down, primary, standby) is remembered for `hostRecheckSeconds`, so later connections try the
likely hosts first.

### Parameter types

Every `PreparedStatement` parameter is sent with the type its setter implies: `setString` (and a
`String`, `Character` or enum in `setObject`) as `TEXT`, `setInt`/`setLong`/... as an integer,
`setDouble`/`setFloat` as a float, `setBigDecimal` (and a `BigInteger`) as `NUMERIC`, `setBoolean` as
`BOOLEAN`, `setBytes` as `BYTEA`, a `UUID` as `UUID`. `setObject(value, java.sql.Types.X)` uses `X`.
So `setString(1, "00123")` stores the text `00123`, not the number 123, and a `BigDecimal` keeps
every digit. Dates, times, arrays and `NULL` are left for the server to type where they are used.

As with other drivers, a string is then not silently read as a number: `WHERE int_column = ?` with
`setString(1, "5")` is a type error. Bind numbers with the numeric setters, or set
`stringType=unspecified` to have string parameters typed by the server from their text again.

The server takes only a literal after `LIKE .. ESCAPE`. A statement that binds it instead
(`like ? escape ?`, as Hibernate 5 criteria and Spring Data's derived queries on it do) still runs:
the driver writes the escape value into the statement as a quoted literal.

### DataSource

Pools and containers configured with a data source class rather than a URL can use
`com.nusadb.jdbc.NusaDataSource`. It has bean properties for `serverName`, `portNumber`,
`databaseName`, `user`, `password`, every option above, and `url` as an alternative to the first three.
It opens a new physical connection per call; pooling is left to the pool.

```java
HikariConfig cfg = new HikariConfig();
cfg.setDataSourceClassName("com.nusadb.jdbc.NusaDataSource");
cfg.addDataSourceProperty("serverName", "db.internal");
cfg.addDataSourceProperty("databaseName", "app");
cfg.addDataSourceProperty("user", "app");
cfg.addDataSourceProperty("password", secret);
```

### Authentication

For a server started with `--auth-user USER:PASSWORD`, supply the password; the
driver runs SCRAM-SHA-256 and verifies the server signature (mutual auth).

### Scrollable result sets

A statement created with `ResultSet.TYPE_SCROLL_INSENSITIVE` returns a result set that moves
freely: `previous`, `absolute`, `relative`, `first`, `last`, `beforeFirst`, `afterLast` and
`getRow`. Its rows are read to the client when the query runs (a fetch size does not stream it).
`TYPE_SCROLL_SENSITIVE` is given as scroll-insensitive. Hibernate's `ScrollableResults` uses this
for `query.scroll()`. A forward-only result set reports its row numbers and position
(`getRow`, `isFirst`, `isLast`) but refuses to move back.

### Updatable result sets

A statement created with `ResultSet.CONCUR_UPDATABLE` gives result sets that change the table:
`updateXxx` then `updateRow()`, `moveToInsertRow()` then `insertRow()`, `deleteRow()`, and
`refreshRow()` to read the row again. The query must select from one table (no join, grouping,
`DISTINCT` or set operation) and include its whole primary key; a result column is written to the
table column of the same name, so an expression or a renamed column cannot be updated. Each change
is one statement on the connection, in its open transaction when there is one, and the row shows
the values as stored afterwards. An inserted row is added at the end of the result set and a
deleted one is taken out of it. Such a result set reads all its rows at once, even with a fetch
size. A query that cannot be written back still runs; the first change then fails with SQLState
`24000` saying why.

### Statement reuse

Frameworks create a new `PreparedStatement` for every query (Hibernate, `JdbcTemplate`). The
driver keeps the server-side statement of each SQL text on the connection, so the next
`PreparedStatement` with the same SQL needs no new Parse and its `close()` frees nothing on the
server: a repeated query is a single round trip. Up to `preparedStatementCacheQueries` statements
(default 256) are kept per connection, the least recently used beyond that are closed. The server
plans every execution anew, so a kept statement stays right when the table changes under it.

### Large results (`setFetchSize`)

With a fetch size (`Statement.setFetchSize`, Spring's `JdbcTemplate.setFetchSize`, Hibernate's
`hibernate.jdbc.fetch_size` or a JPA fetch-size hint) the rows of a query are read from the server
as the result set reaches them, that many at a time, instead of all at once: the first row of a
large result arrives at once and memory stays at about one chunk. This works in autocommit as
well as inside a transaction. Without a fetch size every row is read when the query runs.

While such a result set is open the connection can still run other statements; the result's
remaining rows are then read into memory first. A result set closed before its last row (for
example a `Stream` that is only partly consumed) cancels the query on the server. Inside a
transaction the driver sets a savepoint of its own before a streamed query, so cancelling it does
not abort the transaction; the savepoint is released when the result ends.

### JDBC escape syntax

SQL is translated from the JDBC escape syntax before it is sent, as the JDBC specification asks:
`{d '2026-03-15'}`, `{t '10:20:30'}` and `{ts '...'}` become typed literals, `{fn ...}` the
server's functions (all the scalar functions of the specification: `ucase`, `concat`, `locate`,
`dayofweek`, `timestampadd`, `ifnull`, ...), `{oj ...}` an outer join, `{escape '!'}` a LIKE
escape and `{limit n offset m}` a LIMIT. String literals, quoted names and comments are left
alone, and `Connection.nativeSQL` shows the result. `Statement.setEscapeProcessing(false)` sends
the SQL of that statement as written. `{call ...}` belongs to `prepareCall`, see below.

### Stored functions and procedures

`Connection.prepareCall` returns a `CallableStatement`:

| SQL given | Runs | Parameters |
|---|---|---|
| `{? = call f(?, ?)}` | `SELECT f(?, ?)` | 1 is the return value (`registerOutParameter(1, ..)`, then `getInt(1)` and the like); the arguments are 2 and up |
| `{call f(?, ?)}` | `SELECT f(?, ?) AS result`, read with `executeQuery` | the arguments are 1 and up |
| `{call p(?)}` with `escapeSyntaxCallMode=callIfNoReturn` or `call` | `CALL p(?)`, for a procedure | as written |
| anything else (`CALL p(?)`, `SELECT f(?)`) | as written | as written |

When OUT parameters are registered, they are read from the first row of the result (the return
value first) and `execute()` reports no result set. `getObject` returns the Java type of the
registered SQL type. Parameters are addressed by index: the server keeps no parameter metadata,
so names cannot be resolved, and `getProcedureColumns` / `getFunctionColumns` are empty
(`getProcedures` / `getFunctions` list the routines). Spring's `SimpleJdbcCall` therefore needs
`withoutProcedureColumnMetaDataAccess()` and declared parameters.

### Batch updates

`Statement.addBatch(String)` / `PreparedStatement.addBatch()` + `executeBatch()` are supported for
bulk DML (and `DatabaseMetaData.supportsBatchUpdates()` returns `true`). `executeBatch()` returns
the per-entry update counts.

A batch is pipelined: the entries are sent 256 at a time before their replies are read, so a
batch of thousands of rows takes a few round trips, not one per row. In autocommit mode the whole
batch runs as one transaction, committed when it ends: if an entry fails, the
`BatchUpdateException` carries the counts of the entries before it and nothing of the batch is
kept. Inside a transaction the batch is simply part of it. A batch that asks for generated keys
(`RETURN_GENERATED_KEYS`) still runs one entry at a time.

With `rewriteBatchedInserts=true`, a batch of a plain single-row `INSERT ... VALUES (...)` is sent
as multi-row INSERTs instead, as many rows per statement as the parameter limit allows, which is
faster still. The per-row counts are then unknown, so every entry reports
`Statement.SUCCESS_NO_INFO` (ORMs accept that). Other statements keep the pipelined path.

```java
try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES ($1, $2)")) {
    for (var row : rows) {
        ps.setInt(1, row.id);
        ps.setString(2, row.name);
        ps.addBatch();
    }
    int[] counts = ps.executeBatch();
}
```

The JDBC 4.2 `long` variants are there too: `executeLargeUpdate` (also with generated keys),
`executeLargeBatch`, `getLargeUpdateCount`, `setLargeMaxRows` / `getLargeMaxRows`, and
`BatchUpdateException.getLargeUpdateCounts()`. The `int` methods give a count past
`Integer.MAX_VALUE` as `Integer.MAX_VALUE`.

### Bulk load / export (`COPY`)

For high-throughput load/export, `NusaCopyManager` drives the `COPY` sub-protocol, with one round-trip
for the whole dataset. Unwrap it from the connection, then move bytes in the server's text format
(tab-delimited fields, `\N` for SQL `NULL`, one row per line). You write the `COPY` statement, with
any `WITH (...)` options.

```java
NusaCopyManager copy = conn.unwrap(NusaCopyManager.class);

// Bulk load from any InputStream.
byte[] data = "1\talice\n2\t\\N\n3\tcarol\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
long loaded = copy.copyIn("COPY t (id, name) FROM STDIN", new java.io.ByteArrayInputStream(data));

// Bulk export into any OutputStream.
var out = new java.io.ByteArrayOutputStream();
long exported = copy.copyOut("COPY t TO STDOUT", out);
```

A source read error sends `CopyFail`, and a `COPY` the server refuses (bad SQL, an RLS-protected
table) throws. Either way the connection is left ready for the next statement.

## Transactions

Both autocommit (the default) and explicit transactions are supported. With
autocommit on, the server runs each statement on its own implicit transaction.
Call `setAutoCommit(false)` to begin a unit of work: a `BEGIN` is sent lazily
before the first statement, and `commit()` / `rollback()` close it with
`COMMIT` / `ROLLBACK` over the wire. Switching back with `setAutoCommit(true)`
commits any open transaction (JDBC semantics); closing the connection while a
transaction is open rolls it back.

`setTransactionIsolation` applies the level to the transactions the connection starts from then
on (all four JDBC levels are supported), and `getTransactionIsolation` reports what the server will
use. Changing the level inside an open transaction is refused with SQLState `25001`, since the
server only accepts it before a transaction's first statement. `setReadOnly` is accepted as a hint
but not enforced: the server has no read-only transactions yet.

Inside a transaction, the JDBC savepoint API is supported: `setSavepoint()` /
`setSavepoint(name)` mark a point, `rollback(savepoint)` undoes to it while keeping the
transaction open, and `releaseSavepoint(savepoint)` forgets it.

```java
conn.setAutoCommit(false);
try (Statement st = conn.createStatement()) {
    st.executeUpdate("INSERT INTO t VALUES (1, 'alice')");
    conn.commit();              // or conn.rollback() to discard
}
```

`DatabaseMetaData` is implemented: the introspection a GUI tool such as DBeaver uses
to build its schema tree (`getSchemas`, `getTableTypes`, `getTables`, `getColumns`,
`getTypeInfo`) is answered from the server's `information_schema` views. `getCatalogs`
lists every database in the cluster via `SELECT name FROM nusadb_databases`, NusaDB's own
system catalog (not `information_schema`, which the engine scopes to the connected database),
so the tree shows all databases as siblings.
Because NusaDB is engine-per-database, a connection can still only query its own catalog;
browsing another database opens a new connection.
`getTypeInfo` lists the column types the server takes, ordered by `DATA_TYPE`;
`getNumericFunctions`, `getStringFunctions`, `getSystemFunctions` and `getTimeDateFunctions`
list the `{fn ...}` escapes that run; `getSQLKeywords` lists the server's words beyond SQL:2003.
`getBestRowIdentifier` is the primary key, and `getTablePrivileges` / `getColumnPrivileges`
come from `information_schema.table_privileges`. With the Hibernate dialect, a column or table
named after a word the server reserves (`key`, `index`, `interval`, ...) is quoted for you.
Primary keys (`getPrimaryKeys`) and indexes (`getIndexInfo`) are likewise answered from
the `information_schema.table_constraints` / `key_column_usage` / `statistics` views, so a
tool's schema tree shows PK badges and index lists. `getColumns` reports `COLUMN_SIZE` /
`DECIMAL_DIGITS` from the reflected `character_maximum_length` (`VARCHAR(n)`/`CHAR(n)`) and
`numeric_precision`/`numeric_scale` (`NUMERIC(p,s)`). Foreign keys (`getImportedKeys`,
`getExportedKeys`, `getCrossReference`) come from `information_schema.referential_constraints`
and `key_column_usage`, composite keys included. Procedures, which NusaDB does not model yet,
return an empty result of the JDBC-specified shape. `getDatabaseProductVersion` is the server's
own version (`SHOW server_version`).

`getGeneratedKeys` is supported for INSERTs: when a statement is executed with
`Statement.RETURN_GENERATED_KEYS` (or a column-name list), the driver appends a
`RETURNING` clause and exposes the resulting row(s) through `getGeneratedKeys()`, so
`@GeneratedValue` / auto-increment keys round-trip. (Column-*index* requests return all
columns, since indexes can't be mapped to names without statement metadata.)

`BYTEA` columns round-trip as `byte[]`: `setBytes` / `setObject(byte[])` send the value as
the `\x<hex>` text form (which the server coerces into the `BYTEA` column), and `getBytes`
decodes a BYTEA column's `\x<hex>` back to the raw bytes.

`getObject` returns the Java type matching each column's SQL type: `BOOL` → `Boolean`,
`INT` → `Long`, `FLOAT` → `Double`, `NUMERIC` → `BigDecimal`, `DATE`/`TIME`/`TIMESTAMP`/
`TIMESTAMPTZ` → `java.sql.Date`/`Time`/`Timestamp`, `UUID` → `java.util.UUID`, `BYTEA` → `byte[]`;
`TEXT`, `JSON`, `ARRAY`, and other types come back as `String`. The typed getters (`getInt`,
`getBigDecimal`, `getTimestamp`, …) parse any column on demand regardless; `getInt` and friends
refuse a value that does not fit rather than wrapping it.

### Dates and times

`getObject(column, Class)` (JDBC 4.2) reads `LocalDate`, `LocalTime`, `LocalDateTime`,
`OffsetTime`, `OffsetDateTime`, `ZonedDateTime` and `Instant`, as well as the boxed numbers,
`BigDecimal`, `BigInteger`, `UUID`, `String` and `byte[]`. A `TIMESTAMPTZ` value keeps its exact
instant; a plain `TIMESTAMP` read as an offset type is taken as UTC.

`setObject` accepts the same `java.time` types (a `ZonedDateTime` is sent with its offset), plus
`java.util.Date` and `Calendar`. The `Calendar` overloads of `getTimestamp`/`setTimestamp` (and
`Date`/`Time`) read and write wall-clock time in the calendar's zone, which is what
`hibernate.jdbc.time_zone=UTC` relies on. The server's session time zone is UTC.

## TLS


TLS is supported from 0.2.0 (the 0.1.0 artifact has none): add `?tls=true` to the URL (implicit
TLS 1.2+). Trust a private/self-signed server with `?tls=true&tlsCa=/path/to/ca.pem`; `tlsDomain=`
overrides the verified host name and `tlsInsecure=true` skips verification (dev/test only). These
may also be passed as JDBC `Properties`.

### LOBs and streams

The server has no separate large-object store: a `TEXT` column holds a CLOB's whole value and a
`BYTEA` column a BLOB's. `getClob`/`getBlob` (and `getCharacterStream`/`getBinaryStream`) return the
value already read, and `setClob`/`setBlob`, the `Reader`/`InputStream` setters and a `Clob`/`Blob`
in `setObject` read their source in full and send it as text or bytea. `Connection.createClob()` and
`createBlob()` give an in-memory value to fill. JPA `@Lob String` and `@Lob byte[]` map to these
columns.

### XML (`SQLXML`)

The server has no XML type; an XML document is kept in a `text` column. `Connection.createSQLXML`,
`setSQLXML`, `getSQLXML` (and `getObject(.., SQLXML.class)`, `updateSQLXML`) carry it as text. A
value can be written as a string, a character or byte stream, or through a `DOMResult`,
`SAXResult`, `StAXResult` or `StreamResult`, and read the same ways as a `DOMSource`, `SAXSource`,
`StAXSource` or `StreamSource`. Parsing does not load a DTD or resolve an external entity, so a
document that declares one is refused (SQLState `2200M`). The server does not check that the text
is well-formed XML.

### Arrays

`setArray` / `createArrayOf` (and a Java array or `Collection` in `setObject`) send the array
literal `{a,b}`. An array column is reported as `Types.ARRAY`, and `getArray` / `getObject` return a
`java.sql.Array` whose elements are strings (`null` for `NULL`, `Object[]` for a nested array): the
server does not report the element type. `getString` returns the text form.

## Not implemented yet

`CallableStatement` parameters by name throw `SQLFeatureNotSupportedException` where called.

## Timeouts and cancellation

- `Statement.setQueryTimeout(seconds)` cancels a statement that is still running when the time is
  up; it fails with `SQLTimeoutException` (SQLState `57014`) and the connection stays usable.
- `Statement.cancel()` from another thread stops the statement running on that statement object.
  It is a no-op when nothing is running.
- `Connection.setNetworkTimeout` / `socketTimeout` bound how long to wait for any reply. When that
  expires the connection is closed and reports `isClosed() == true`.
- `Connection.isValid(seconds)` is a round trip that must answer within the timeout.
- `Connection.abort(executor)` drops the socket at once without waiting on the server, which is what
  pools need for a stuck connection; the server rolls back whatever was open.

Cancellation uses the protocol's out-of-band cancel request: the driver opens a short-lived second
connection (over TLS too, when the main one uses it) carrying the connection's cancel key.

## Notifications (LISTEN/NOTIFY)

JDBC has no standard notification surface, so reach it by unwrapping the connection to
`NusaListener`. `listen(channel)` subscribes; a `notify(channel, payload)` from any connection on the
same database is delivered asynchronously. `pollNotification(timeoutMillis)` waits for the next one
(`0` blocks), or `getNotifications()` drains those buffered during other queries:

```java
NusaListener l = conn.unwrap(NusaListener.class);
l.listen("orders");
// ... elsewhere: l.notify("orders", "42");
NusaNotification note = l.pollNotification(5000); // -> NusaNotification, or null on timeout
System.out.println(note.getChannel() + " " + note.getPayload());
l.unlisten("orders");
```

## Build & test

The repository is one Maven build (JDK 17+, Maven 3.6.3+):

| Module | Artifact | |
|---|---|---|
| `nusadb-jdbc/` | `com.nusadb:nusadb-jdbc` | this driver |
| `hibernate5/` | `com.nusadb:nusadb-hibernate5` | [Hibernate ORM 5.x dialect](hibernate5/README.md) |
| `hibernate6/` | `com.nusadb:nusadb-hibernate6` | [Hibernate ORM 6.x dialect](hibernate6/README.md) |
| `bom/` | `com.nusadb:nusadb-bom` | pins all of the above to one version |
| `test-support/` | (not published) | internal: provides a live server to the integration tests |

```bash
mvn verify          # build every module + unit tests (no server needed)
mvn verify -Pit     # + integration tests (*IT) against a live NusaDB server
```

The integration tests get their server from `NusaTestServer` (in `test-support/`), first match wins:

| Setting | Server used |
|---|---|
| `NUSADB_TEST_URL=jdbc:nusadb://user:pass@host:port/db` | an already running server. **Every table, view and sequence in that database is dropped first**, so use a scratch database |
| `NUSADB_SERVER_BIN=/path/to/nusadb-server` | that binary, started on an ephemeral port with a temp data dir |
| *(neither)* | a [Testcontainers](https://testcontainers.com/) container of `nusadb/nusadb` (needs Docker; override the image with `NUSADB_TEST_IMAGE`) |

The TLS tests (`TlsIT`) need a server started with `--tls-cert/--tls-key` and run only when
`NUSADB_TEST_TLS_URL` names it, e.g. `jdbc:nusadb://user:pass@localhost:5680/nusadb?tls=true&tlsCa=/path/server.crt`;
without it they are skipped.

The version of every module is the `revision` property in the root `pom.xml`; published poms
are flattened, so they do not reference the (unpublished) build parent.

## License

Apache-2.0.
