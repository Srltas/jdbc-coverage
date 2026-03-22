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
