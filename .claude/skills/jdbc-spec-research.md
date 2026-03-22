---
name: jdbc-spec-research
description: >
  Research JDBC specification details — which version introduced a method,
  whether it's optional/deprecated, and spec compliance requirements.
  Use when the user asks about JDBC spec versions, method history, or spec mapping accuracy.
  Trigger on: "JDBC 스펙", "몇 버전부터", "since", "@since", "스펙 확인", "spec check".
trigger: always
---

# JDBC Spec Research Skill

## Purpose

Accurately research and verify JDBC interface method specifications, including
the version in which each method was introduced, optional status, and deprecation.

## JDBC Version History

| JDBC Version | Java Version | JSR | Key Additions |
|---|---|---|---|
| 1.0 | JDK 1.1 | — | Core: Connection, Statement, ResultSet |
| 2.0 | JDK 1.2 | — | Scrollable ResultSet, Batch updates, BLOB/CLOB |
| 2.1 | JDK 1.2 | — | javax.sql: DataSource, RowSet, ConnectionPooling |
| 3.0 | JDK 1.4 | JSR-54 | Savepoints, ParameterMetaData, auto-generated keys |
| 4.0 | Java 6 | JSR-221 | XML type, Wrapper, RowId, SQLXML, NClob |
| 4.1 | Java 7 | — | try-with-resources, pseudo columns, large update count |
| 4.2 | Java 8 | — | REF_CURSOR, SQLType, executeLargeUpdate, default methods |
| 4.3 | Java 9 | — | Connection sharding, beginRequest/endRequest |

## Key Interfaces to Track

### Core (java.sql)
- `Connection` — ~70 methods
- `Statement` — ~40 methods
- `PreparedStatement` — ~60 methods (extends Statement)
- `CallableStatement` — ~70 methods (extends PreparedStatement)
- `ResultSet` — ~180 methods
- `DatabaseMetaData` — ~150 methods
- `ResultSetMetaData` — ~20 methods
- `ParameterMetaData` — ~10 methods (since 3.0)
- `Driver` — ~8 methods
- `Blob` / `Clob` / `NClob` — ~10 methods each
- `SQLXML` — ~8 methods (since 4.0)
- `Array` / `Struct` / `Ref` — rarely fully implemented
- `Wrapper` — 2 methods (since 4.0)

### Extended (javax.sql)
- `DataSource` — ~5 methods
- `ConnectionPoolDataSource`
- `XADataSource`
- `PooledConnection`
- `XAConnection`
- `RowSet` and subinterfaces

## Research Process

1. **Primary source**: OpenJDK source code — `@since` tag on each method
2. **Cross-reference**: Oracle JDBC Javadoc for each Java version
3. **Validation**: Compare with actual JDK bytecode across versions (JDK 6, 7, 8, 9, 11)

## Common Pitfalls

- `Connection.isValid()` — added in JDBC 4.0, but frequently unimplemented by drivers
- `ResultSet.getObject(int, Class<T>)` — added in JDBC 4.1 as a new overload; easy to overlook
- **Default methods in JDBC 4.2** — e.g., `Statement.executeLargeUpdate()` has a default interface
  implementation that throws `UnsupportedOperationException`, so it compiles without override
  but is effectively unimplemented at runtime
- `Wrapper.unwrap()` / `Wrapper.isWrapperFor()` — since all JDBC 4.0 interfaces extend `Wrapper`,
  all implementing classes must provide these methods; often missed

## OpenJDK Source Locations

```
# JDBC core interfaces
src/java.sql/share/classes/java/sql/
src/java.sql/share/classes/javax/sql/

# OpenJDK GitHub
https://github.com/openjdk/jdk/tree/master/src/java.sql/share/classes/java/sql/
```

## Useful Web Resources

- OpenJDK source: `https://github.com/openjdk/jdk`
- Java SE API docs: `https://docs.oracle.com/en/java/javase/{version}/docs/api/java.sql/java/sql/`
