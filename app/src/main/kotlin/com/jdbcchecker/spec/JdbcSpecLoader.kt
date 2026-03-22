package com.jdbcchecker.spec

import com.jdbcchecker.model.JdbcVersion
import com.jdbcchecker.model.MethodSignature

/**
 * Loads JDBC specification method definitions from YAML files.
 *
 * Spec data is stored in resources/jdbc-spec/ directory,
 * organized by interface name (e.g., java.sql.Connection.yaml).
 */
class JdbcSpecLoader {

    /**
     * Load all JDBC spec methods from bundled YAML resources.
     */
    fun loadAll(): List<MethodSignature> {
        return JDBC_INTERFACES.flatMap { loadInterface(it) }
    }

    /**
     * Load spec methods for a specific interface.
     */
    fun loadInterface(interfaceName: String): List<MethodSignature> {
        val resourcePath = "/jdbc-spec/$interfaceName.yaml"
        val stream = javaClass.getResourceAsStream(resourcePath)
            ?: return emptyList()

        return stream.use { parseYaml(interfaceName, it) }
    }

    private fun parseYaml(interfaceName: String, input: java.io.InputStream): List<MethodSignature> {
        // TODO: Implement YAML parsing with Jackson
        return emptyList()
    }

    companion object {
        /** Core JDBC interfaces to track */
        val JDBC_INTERFACES = listOf(
            "java.sql.Connection",
            "java.sql.Statement",
            "java.sql.PreparedStatement",
            "java.sql.CallableStatement",
            "java.sql.ResultSet",
            "java.sql.DatabaseMetaData",
            "java.sql.ResultSetMetaData",
            "java.sql.ParameterMetaData",
            "java.sql.Driver",
            "java.sql.Blob",
            "java.sql.Clob",
            "java.sql.NClob",
            "java.sql.SQLXML",
            "java.sql.Array",
            "java.sql.Struct",
            "java.sql.Ref",
            "java.sql.Wrapper",
            "javax.sql.DataSource",
            "javax.sql.ConnectionPoolDataSource",
            "javax.sql.CommonDataSource",
        )
    }
}
