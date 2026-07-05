package com.jdbcchecker.spec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jdbcchecker.model.JdbcVersion
import com.jdbcchecker.model.MethodSignature
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/**
 * Loads JDBC specification method definitions from YAML files.
 *
 * Supports two modes:
 * - Bundled resources: loads from resources/jdbc-spec/ (default)
 * - External directory: loads from a specified directory path
 */
class JdbcSpecLoader {

    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /**
     * Load all JDBC spec methods from bundled YAML resources.
     */
    fun loadAll(): List<MethodSignature> {
        return JDBC_INTERFACES.flatMap { loadInterface(it) }
    }

    /**
     * Load all JDBC spec methods from an external directory.
     */
    fun loadAllFromDirectory(specDir: Path): List<MethodSignature> {
        if (!Files.isDirectory(specDir)) {
            System.err.println("Warning: Spec directory not found: $specDir")
            return loadAll() // fallback to bundled resources
        }

        return Files.list(specDir)
            .filter { it.isRegularFile() && it.extension == "yaml" }
            .filter { !it.nameWithoutExtension.startsWith("_") }
            .map { file ->
                val interfaceName = file.nameWithoutExtension
                Files.newInputStream(file).use { parseYaml(interfaceName, it) }
            }
            .flatMap { it.stream() }
            .toList()
    }

    /**
     * Load spec methods for a specific interface from bundled resources.
     */
    fun loadInterface(interfaceName: String): List<MethodSignature> {
        val resourcePath = "/jdbc-spec/$interfaceName.yaml"
        val stream = javaClass.getResourceAsStream(resourcePath)
            ?: return emptyList()

        return stream.use { parseYaml(interfaceName, it) }
    }

    /**
     * Parse a YAML spec file into MethodSignature objects.
     */
    internal fun parseYaml(interfaceName: String, input: InputStream): List<MethodSignature> {
        val tree = yamlMapper.readTree(input) ?: return emptyList()
        val methodsNode = tree.get("methods") ?: return emptyList()

        return methodsNode.mapNotNull { methodNode ->
            val name = methodNode.get("name")?.asText() ?: return@mapNotNull null
            val params = methodNode.get("params")
                ?.map { it.asText() }
                ?: emptyList()
            val returns = methodNode.get("returns")?.asText() ?: "void"
            val since = methodNode.get("since")?.asText() ?: "1.0"

            MethodSignature(
                interfaceName = interfaceName,
                methodName = name,
                parameterTypes = params,
                returnType = returns,
                jdbcVersion = JdbcVersion.fromString(since) ?: JdbcVersion.V1_0,
            )
        }
    }

    companion object {
        /**
         * Frozen spec identifier stamped into every snapshot. Bump ONLY when the
         * bundled YAML set or its scope changes — a bump rebases the daily history.
         */
        const val SPEC_VERSION = "spec-1"

        /**
         * Core JDBC interfaces tracked by the spec loader.
         *
         * Source of truth: JDK 26 (Temurin 26.0.1), modules `java.sql` and
         * `java.transaction.xa`. The `java.sql.rowset` module is intentionally
         * excluded — RowSet is a client-side container API that JDBC drivers
         * do not implement (verified against five major drivers: CUBRID,
         * pgjdbc, MySQL, MariaDB, MSSQL — all zero implementations).
         *
         * 34 interfaces total. Like RowSet, java.sql.SQLData (implemented by
         * application code) and javax.sql.Connection/StatementEventListener
         * (implemented by pool managers) are excluded — drivers do not
         * implement them. java.sql.NClob and java.sql.ShardingKey are also
         * excluded: they declare no methods of their own, so they contribute
         * nothing to a method-coverage metric.
         */
        val JDBC_INTERFACES = listOf(
            // ── java.sql ─────────────────────────────────────────────────────
            "java.sql.Connection",            // JDBC 1.0
            "java.sql.Statement",             // JDBC 1.0
            "java.sql.PreparedStatement",     // JDBC 1.0
            "java.sql.CallableStatement",     // JDBC 1.0
            "java.sql.ResultSet",             // JDBC 1.0
            "java.sql.DatabaseMetaData",      // JDBC 1.0
            "java.sql.Driver",                // JDBC 1.0
            "java.sql.SQLInput",              // JDBC 2.0
            "java.sql.SQLOutput",             // JDBC 2.0
            "java.sql.Array",                 // JDBC 2.0
            "java.sql.Struct",                // JDBC 2.0
            "java.sql.Ref",                   // JDBC 2.0
            "java.sql.Blob",                  // JDBC 2.0
            "java.sql.Clob",                  // JDBC 2.0
            "java.sql.Savepoint",             // JDBC 3.0
            "java.sql.ParameterMetaData",     // JDBC 3.0
            "java.sql.ResultSetMetaData",     // JDBC 1.0
            "java.sql.SQLXML",                // JDBC 4.0
            "java.sql.RowId",                 // JDBC 4.0
            "java.sql.Wrapper",               // JDBC 4.0
            "java.sql.DriverAction",          // JDBC 4.1 (Java 1.8)
            "java.sql.SQLType",               // JDBC 4.2 (Java 1.8)
            "java.sql.ConnectionBuilder",     // JDBC 4.3 (Java 9)
            "java.sql.ShardingKeyBuilder",    // JDBC 4.3 (Java 9)
            // ── javax.sql ────────────────────────────────────────────────────
            "javax.sql.CommonDataSource",     // JDBC 4.1 (refactor of DataSource)
            "javax.sql.DataSource",           // JDBC 2.0
            "javax.sql.ConnectionPoolDataSource", // JDBC 2.0
            "javax.sql.PooledConnection",     // JDBC 2.0
            "javax.sql.XAConnection",         // JDBC 2.0
            "javax.sql.XADataSource",         // JDBC 2.0
            "javax.sql.PooledConnectionBuilder",  // JDBC 4.3 (Java 9)
            "javax.sql.XAConnectionBuilder",  // JDBC 4.3 (Java 9)
            // ── javax.transaction.xa (JTA, required by XAConnection) ────────
            "javax.transaction.xa.XAResource",
            "javax.transaction.xa.Xid",
        )
    }
}
