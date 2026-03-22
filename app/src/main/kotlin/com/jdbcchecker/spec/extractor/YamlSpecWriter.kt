package com.jdbcchecker.spec.extractor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jdbcchecker.model.MethodSignature
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes extracted JDBC spec method signatures to YAML files.
 *
 * Output structure:
 * ```yaml
 * interface: java.sql.Connection
 * since: "1.0"
 * methods:
 *   - name: setAutoCommit
 *     params: [boolean]
 *     returns: void
 *     since: "1.0"
 * ```
 */
class YamlSpecWriter {

    private val mapper = ObjectMapper(
        YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    ).registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    /**
     * Write all method signatures grouped by interface to YAML files.
     *
     * @param methods All extracted method signatures
     * @param outputDir Directory to write YAML files to
     */
    fun writeAll(methods: List<MethodSignature>, outputDir: Path) {
        Files.createDirectories(outputDir)

        val byInterface = methods.groupBy { it.interfaceName }

        byInterface.forEach { (interfaceName, interfaceMethods) ->
            writeInterface(interfaceName, interfaceMethods, outputDir)
        }

        // Write summary
        writeSummary(byInterface, outputDir)

        println("Wrote ${byInterface.size} interface spec files to $outputDir")
        println("Total methods: ${methods.size}")
    }

    /**
     * Write a single interface's methods to a YAML file.
     */
    private fun writeInterface(
        interfaceName: String,
        methods: List<MethodSignature>,
        outputDir: Path,
    ) {
        val sortedMethods = methods.sortedWith(
            compareBy({ it.jdbcVersion }, { it.methodName }, { it.parameterTypes.size })
        )

        val spec = InterfaceSpec(
            interfaceName = interfaceName,
            since = sortedMethods.first().jdbcVersion.display,
            methodCount = sortedMethods.size,
            methods = sortedMethods.map { method ->
                MethodSpec(
                    name = method.methodName,
                    params = method.parameterTypes,
                    returns = method.returnType,
                    since = method.jdbcVersion.display,
                )
            }
        )

        val fileName = "$interfaceName.yaml"
        val outputPath = outputDir.resolve(fileName)
        mapper.writeValue(outputPath.toFile(), spec)
    }

    /**
     * Write a summary file listing all interfaces and their method counts.
     */
    private fun writeSummary(
        byInterface: Map<String, List<MethodSignature>>,
        outputDir: Path,
    ) {
        val summary = SpecSummary(
            totalInterfaces = byInterface.size,
            totalMethods = byInterface.values.sumOf { it.size },
            interfaces = byInterface.map { (name, methods) ->
                InterfaceSummary(
                    name = name,
                    methodCount = methods.size,
                    since = methods.minOf { it.jdbcVersion }.display,
                )
            }.sortedBy { it.name }
        )

        mapper.writeValue(outputDir.resolve("_summary.yaml").toFile(), summary)
    }

    // Data classes for YAML serialization
    data class InterfaceSpec(
        val interfaceName: String,
        val since: String,
        val methodCount: Int,
        val methods: List<MethodSpec>,
    )

    data class MethodSpec(
        val name: String,
        val params: List<String>,
        val returns: String,
        val since: String,
    )

    data class SpecSummary(
        val totalInterfaces: Int,
        val totalMethods: Int,
        val interfaces: List<InterfaceSummary>,
    )

    data class InterfaceSummary(
        val name: String,
        val methodCount: Int,
        val since: String,
    )
}
