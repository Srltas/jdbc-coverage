package com.jdbcchecker.cli

import com.jdbcchecker.spec.extractor.SpecExtractor
import com.jdbcchecker.spec.extractor.YamlSpecWriter
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "extract-spec",
    description = ["Extract JDBC spec method definitions from JDK source files."],
    mixinStandardHelpOptions = true,
)
class ExtractSpecCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = [
            "Path to JDK source root. Two layouts are supported:",
            "  Module directory:  path/to/java.sql/  (java/sql/ directly inside)",
            "  Modules parent:    path/to/src/        (java.sql/, java.transaction.xa/ as subdirs)",
            "The java.transaction.xa module is auto-located as a sibling or child directory.",
        ],
    )
    lateinit var jdkSourceRoot: Path

    @Option(
        names = ["-o", "--output"],
        description = ["Output directory for YAML spec files (default: jdbc-spec/)."],
        defaultValue = "jdbc-spec",
    )
    lateinit var outputDir: Path

    @Option(
        names = ["--interfaces"],
        description = ["Comma-separated list of interfaces to extract (default: all)."],
        split = ",",
    )
    var interfaces: List<String> = emptyList()

    override fun call(): Int {
        println("JDBC Spec Extractor")
        println("JDK Source: $jdkSourceRoot")
        println("Output: $outputDir")
        println()

        return try {
            val extractor = SpecExtractor(jdkSourceRoot)
            val targetInterfaces = interfaces.toSet()

            println("Extracting method signatures...")
            val methods = extractor.extractAll(targetInterfaces)

            if (methods.isEmpty()) {
                System.err.println("No methods found. Check the JDK source path.")
                return 1
            }

            println("Found ${methods.size} methods across ${methods.map { it.interfaceName }.distinct().size} interfaces")
            println()

            // Print per-version summary
            val byVersion = methods.groupBy { it.jdbcVersion }
            byVersion.toSortedMap().forEach { (version, versionMethods) ->
                println("  JDBC ${version.display}: ${versionMethods.size} methods")
            }
            println()

            val writer = YamlSpecWriter()
            writer.writeAll(methods, outputDir)

            println()
            println("Extraction complete!")
            0
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            e.printStackTrace()
            1
        }
    }
}
