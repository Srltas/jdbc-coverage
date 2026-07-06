package com.jdbccoverage.spec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jdbccoverage.model.JdbcScope
import com.jdbccoverage.model.JdbcVersion
import com.jdbccoverage.model.MethodSignature
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
         * bundled YAML set, its scope, or the metric's grouping changes — a bump
         * rebases the daily history.
         *
         * spec-2: same 34 interfaces / 889 methods as spec-1, but coverage is now
         * reported per [SpecGroup] — the headline is MAIN (816), with PERIPHERAL (60)
         * and XA/JTA (13) as separate metrics (previously one flat 889 number).
         */
        const val SPEC_VERSION = "spec-2"

        /**
         * The 34 measured JDBC interfaces (JDK 26 / Temurin 26.0.1, verified by reflection).
         * The interface set, its scope rationale, and the [SpecGroup] partition are defined
         * in [com.jdbccoverage.model.JdbcScope].
         */
        val JDBC_INTERFACES: List<String> = JdbcScope.ALL
    }
}
