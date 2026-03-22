package com.jdbcchecker.cli

import com.jdbcchecker.detector.ImplementationDetector
import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.InterfaceResult
import com.jdbcchecker.model.MethodResult
import com.jdbcchecker.model.MethodSignature
import com.jdbcchecker.parser.SourceParser
import com.jdbcchecker.report.console.ConsoleReporter
import com.jdbcchecker.report.json.JsonReporter
import com.jdbcchecker.resolver.JdbcInterfaceResolver
import com.jdbcchecker.spec.JdbcSpecLoader
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable

@Command(
    name = "jdbc-checker",
    mixinStandardHelpOptions = true,
    version = ["jdbc-compliance-checker 0.1.0"],
    description = ["Analyze JDBC driver source code for spec compliance."],
    subcommands = [
        AnalyzeCommand::class,
        ExtractSpecCommand::class,
    ],
)
class JdbcCheckerCommand : Runnable {
    override fun run() {
        CommandLine.usage(this, System.out)
    }
}

@Command(
    name = "analyze",
    description = ["Analyze JDBC driver source code for spec compliance."],
    mixinStandardHelpOptions = true,
)
class AnalyzeCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["Path to JDBC driver source root directory."],
    )
    lateinit var sourcePath: Path

    @Option(
        names = ["-o", "--output"],
        description = ["Output format: console, json:<path>. Can be specified multiple times."],
    )
    var outputs: List<String> = listOf("console")

    @Option(
        names = ["-n", "--driver-name"],
        description = ["Driver name for the report (auto-detected if not specified)."],
    )
    var driverName: String? = null

    @Option(
        names = ["--entry-class"],
        description = ["Manually specify implementing class (overrides auto-detection)."],
    )
    var entryClasses: List<String> = emptyList()

    @Option(
        names = ["-s", "--spec-dir"],
        description = ["Path to external JDBC spec YAML directory (uses bundled specs if not specified)."],
    )
    var specDir: Path? = null

    override fun call(): Int {
        println("JDBC Compliance Checker v0.1.0")
        println("Source: $sourcePath")
        println()

        if (!Files.isDirectory(sourcePath)) {
            System.err.println("Error: Source path does not exist or is not a directory: $sourcePath")
            return 1
        }

        // Step 1: Load JDBC spec
        print("Loading JDBC specification... ")
        val specLoader = JdbcSpecLoader()
        val specMethods = specDir?.let { specLoader.loadAllFromDirectory(it) }
            ?: specLoader.loadAll()

        if (specMethods.isEmpty()) {
            System.err.println("Error: No spec methods loaded. Check spec YAML files.")
            return 1
        }

        val specByInterface = specMethods.groupBy { it.interfaceName }
        println("${specMethods.size} methods across ${specByInterface.size} interfaces")

        // Step 2: Parse source files
        print("Parsing source files... ")
        val parser = SourceParser(listOf(sourcePath))
        val compilationUnits = parser.parseAll()
        println("${compilationUnits.size} files parsed")

        if (compilationUnits.isEmpty()) {
            System.err.println("Error: No Java source files found in $sourcePath")
            return 1
        }

        // Step 3: Resolve JDBC interface implementors
        print("Resolving JDBC interface implementations... ")
        val resolver = JdbcInterfaceResolver()
        val implementors = resolver.resolve(compilationUnits)
        println("${implementors.size} interfaces matched")

        if (implementors.isEmpty()) {
            System.err.println("Warning: No JDBC interface implementations found.")
        }

        // Step 4: Detect implementation status for each method
        print("Analyzing implementation status... ")
        val detector = ImplementationDetector()
        detector.registerCompilationUnits(compilationUnits)
        val interfaceResults = specByInterface.map { (interfaceName, methods) ->
            val classDecl = implementors[interfaceName]
            val implClassName = classDecl?.let { decl ->
                val pkg = decl.findCompilationUnit()
                    .flatMap { it.packageDeclaration }
                    .map { it.nameAsString }
                    .orElse("")
                if (pkg.isEmpty()) decl.nameAsString else "$pkg.${decl.nameAsString}"
            }

            val methodResults = methods.map { specMethod ->
                if (classDecl != null) {
                    val status = detector.detect(specMethod, classDecl)
                    MethodResult(
                        specMethod = specMethod,
                        status = status,
                        implementingClass = implClassName,
                    )
                } else {
                    MethodResult(
                        specMethod = specMethod,
                        status = ImplementationStatus.NotFound,
                    )
                }
            }

            InterfaceResult(
                interfaceName = interfaceName,
                implementingClass = implClassName,
                methods = methodResults,
            )
        }
        println("done")

        // Step 5: Build report
        val resolvedDriverName = driverName ?: detectDriverName(sourcePath)
        val report = AnalysisReport(
            driverName = resolvedDriverName,
            sourcePath = sourcePath.toAbsolutePath().toString(),
            analyzedAt = Instant.now(),
            interfaces = interfaceResults,
        )

        // Step 6: Generate outputs
        println()
        for (output in outputs) {
            when {
                output == "console" -> ConsoleReporter().report(report)
                output.startsWith("json:") -> {
                    val jsonPath = Path.of(output.removePrefix("json:"))
                    JsonReporter().report(report, jsonPath)
                }
                else -> System.err.println("Warning: Unknown output format: $output")
            }
        }

        return 0
    }

    /**
     * Attempt to detect driver name from the source path.
     */
    private fun detectDriverName(path: Path): String {
        val dirName = path.toAbsolutePath().fileName?.toString() ?: "Unknown"
        return when {
            "cubrid" in dirName.lowercase() -> "CUBRID JDBC"
            "mysql" in dirName.lowercase() -> "MySQL Connector/J"
            "mariadb" in dirName.lowercase() -> "MariaDB Connector/J"
            "postgresql" in dirName.lowercase() || "pgjdbc" in dirName.lowercase() -> "PostgreSQL JDBC"
            else -> dirName
        }
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(JdbcCheckerCommand()).execute(*args)
    System.exit(exitCode)
}
