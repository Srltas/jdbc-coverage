package com.jdbccoverage.model

/**
 * Scope groups for the JDBC coverage metric. See README "측정 스코프".
 *
 * A method is measured iff its interface satisfies **both**:
 *   1. package is `java.sql` / `javax.sql` (JDBC API) — or `javax.transaction.xa` for the XA group,
 *   2. the JDBC **driver** is the implementor (not the application, the JDK, a connection
 *      pool / app-server, or a transaction manager).
 *
 * [MAIN] is the headline coverage. [PERIPHERAL] and [XA] are measured too but reported as
 * separate metrics, so core completeness isn't diluted by rarely-implemented or non-JDBC surface.
 */
enum class SpecGroup(val display: String) {
    MAIN("JDBC"),
    PERIPHERAL("Peripheral"),
    XA("XA/JTA"),
}

/**
 * The frozen set of measured JDBC interfaces, partitioned into [SpecGroup]s.
 * Single source of truth for [com.jdbccoverage.spec.JdbcSpecLoader.JDBC_INTERFACES].
 *
 * Excluded entirely (fail the selection rule above):
 *  - `java.sql.SQLData` — the **application** implements it (UDT ↔ Java class mapping);
 *    the driver only implements the [PERIPHERAL] `SQLInput`/`SQLOutput` it hands to it.
 *  - `javax.sql.ConnectionEventListener` / `StatementEventListener` — the **pool/app-server**
 *    implements these (the driver's `PooledConnection` calls back into them).
 *  - `javax.sql.rowset.*` (RowSet family) — client-side container API; no driver implements it.
 *  - `java.sql.NClob` / `java.sql.ShardingKey` — declare zero methods of their own.
 *  - `javax.naming.Referenceable` (JNDI) / `java.io.Serializable` — outside the JDBC packages.
 */
object JdbcScope {

    // ── MAIN — headline JDBC (java.sql + javax.sql, driver-implemented) ─────────────

    /** Core execution & metadata — every driver implements these. */
    private val CORE = listOf(
        "java.sql.Driver",
        "java.sql.Connection",
        "java.sql.Statement",
        "java.sql.PreparedStatement",
        "java.sql.CallableStatement",
        "java.sql.ResultSet",
        "java.sql.DatabaseMetaData",
        "java.sql.ResultSetMetaData",
        "java.sql.ParameterMetaData",
        "java.sql.Savepoint",
    )

    /** SQL data-type objects the driver returns. */
    private val DATA_TYPES = listOf(
        "java.sql.Blob",
        "java.sql.Clob",
        "java.sql.Array",
        "java.sql.Struct",
        "java.sql.Ref",
        "java.sql.RowId",
        "java.sql.SQLXML",
    )

    /** Base contract mixed into every JDBC 4.0+ interface. */
    private val BASE = listOf("java.sql.Wrapper")

    /** Connection provisioning: DataSource / pooling / XA connection factories + builders. */
    private val PROVISIONING = listOf(
        "javax.sql.CommonDataSource",
        "javax.sql.DataSource",
        "javax.sql.ConnectionPoolDataSource",
        "javax.sql.XADataSource",
        "javax.sql.PooledConnection",
        "javax.sql.XAConnection",
        "javax.sql.PooledConnectionBuilder",
        "javax.sql.XAConnectionBuilder",
        "java.sql.ConnectionBuilder",
        "java.sql.ShardingKeyBuilder",
    )

    /** Headline JDBC surface (28 interfaces, 816 methods). */
    val MAIN_INTERFACES: List<String> = CORE + DATA_TYPES + BASE + PROVISIONING

    // ── PERIPHERAL — driver-implementable but optional / rarely implemented (60) ─────

    /** UDT custom-mapping streams (driver side), vendor-type marker, deregister callback. */
    val PERIPHERAL_INTERFACES = listOf(
        "java.sql.SQLInput",
        "java.sql.SQLOutput",
        "java.sql.SQLType",
        "java.sql.DriverAction",
    )

    // ── XA / JTA — not JDBC API (javax.transaction.xa), reported separately (13) ─────

    /** `Xid` is created by the transaction manager (not the driver) but kept here for XA visibility. */
    val XA_INTERFACES = listOf(
        "javax.transaction.xa.XAResource",
        "javax.transaction.xa.Xid",
    )

    /** Every interface measured by the tool (MAIN + PERIPHERAL + XA) — 34 total. */
    val ALL: List<String> = MAIN_INTERFACES + PERIPHERAL_INTERFACES + XA_INTERFACES

    private val byInterface: Map<String, SpecGroup> = buildMap {
        MAIN_INTERFACES.forEach { put(it, SpecGroup.MAIN) }
        PERIPHERAL_INTERFACES.forEach { put(it, SpecGroup.PERIPHERAL) }
        XA_INTERFACES.forEach { put(it, SpecGroup.XA) }
    }

    /** Group for a fully-qualified interface name; anything unlisted defaults to [SpecGroup.MAIN]. */
    fun groupOf(interfaceName: String): SpecGroup = byInterface[interfaceName] ?: SpecGroup.MAIN
}
