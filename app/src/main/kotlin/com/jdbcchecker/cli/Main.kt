package com.jdbcchecker.cli

import com.jdbcchecker.detector.ImplementationDetector
import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.InterfaceResult
import com.jdbcchecker.model.MethodResult
import com.jdbcchecker.parser.SourceParser
import com.jdbcchecker.report.computeComparison
import com.jdbcchecker.report.computeDiff
import com.jdbcchecker.report.console.ComparisonReporter
import com.jdbcchecker.report.console.ConsoleReporter
import com.jdbcchecker.report.console.DiffReporter
import com.jdbcchecker.report.html.HtmlReporter
import com.jdbcchecker.report.json.DiffJsonReporter
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
    version = ["jdbc-compliance-checker 0.3.0"],
    description = ["Analyze JDBC driver source code for spec compliance."],
    subcommands = [
        AnalyzeCommand::class,
        DiffCommand::class,
        CompareCommand::class,
        ExtractSpecCommand::class,
    ],
)
class JdbcCheckerCommand : Runnable {
    override fun run() {
        CommandLine.usage(this, System.out)
    }
}

// ---------------------------------------------------------------------------
// Shared analysis pipeline
// ---------------------------------------------------------------------------

/**
 * Runs the full analysis pipeline on a JDBC driver source directory.
 *
 * @param sourcePath path to the source root
 * @param driverName optional driver name override (auto-detected if null)
 * @param entryClasses optional manual class overrides for interface detection
 * @param specDir optional external spec YAML directory (uses bundled if null)
 * @return [AnalysisReport] or null if the pipeline fails
 */
internal fun runAnalysis(
    sourcePath: Path,
    driverName: String?,
    entryClasses: List<String> = emptyList(),
    specDir: Path? = null,
): AnalysisReport? {
    if (!Files.isDirectory(sourcePath)) {
        System.err.println("Error: Source path does not exist or is not a directory: $sourcePath")
        return null
    }

    // Step 1: Load JDBC spec
    print("Loading JDBC specification... ")
    val specLoader = JdbcSpecLoader()
    val specMethods = specDir?.let { specLoader.loadAllFromDirectory(it) } ?: specLoader.loadAll()
    if (specMethods.isEmpty()) {
        System.err.println("Error: No spec methods loaded. Check spec YAML files.")
        return null
    }
    val specByInterface = specMethods.groupBy { it.interfaceName }
    println("${specMethods.size} methods across ${specByInterface.size} interfaces")

    // Step 2: Parse source files
    print("Parsing source files... ")
    val compilationUnits = SourceParser(listOf(sourcePath)).parseAll()
    println("${compilationUnits.size} files parsed")
    if (compilationUnits.isEmpty()) {
        System.err.println("Error: No Java source files found in $sourcePath")
        return null
    }

    // Step 3: Resolve JDBC interface implementors
    print("Resolving JDBC interface implementations... ")
    val implementors = JdbcInterfaceResolver().resolve(compilationUnits)
    println("${implementors.size} interfaces matched")

    // Step 4: Detect implementation status per method
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
                MethodResult(
                    specMethod = specMethod,
                    status = detector.detect(specMethod, classDecl),
                    implementingClass = implClassName,
                )
            } else {
                MethodResult(specMethod = specMethod, status = ImplementationStatus.NotFound)
            }
        }

        InterfaceResult(
            interfaceName = interfaceName,
            implementingClass = implClassName,
            methods = methodResults,
        )
    }
    println("done")

    return AnalysisReport(
        driverName = driverName ?: detectDriverName(sourcePath),
        sourcePath = sourcePath.toAbsolutePath().toString(),
        analyzedAt = Instant.now(),
        interfaces = interfaceResults,
    )
}

/** Attempt to detect driver name from the source path directory name. */
internal fun detectDriverName(path: Path): String {
    val dirName = path.toAbsolutePath().fileName?.toString() ?: "Unknown"
    return when {
        "cubrid" in dirName.lowercase() -> "CUBRID JDBC"
        "mysql" in dirName.lowercase() -> "MySQL Connector/J"
        "mariadb" in dirName.lowercase() -> "MariaDB Connector/J"
        "postgresql" in dirName.lowercase() || "pgjdbc" in dirName.lowercase() -> "PostgreSQL JDBC"
        else -> dirName
    }
}

/** Dispatch report outputs (console / json:<path> / html:<path>). */
internal fun dispatchOutputs(outputs: List<String>, report: AnalysisReport) {
    for (output in outputs) {
        when {
            output == "console" -> ConsoleReporter().report(report)
            output.startsWith("json:") -> {
                val path = Path.of(output.removePrefix("json:"))
                JsonReporter().report(report, path)
            }
            output.startsWith("html:") -> {
                val path = Path.of(output.removePrefix("html:"))
                HtmlReporter().report(report, path)
            }
            else -> System.err.println("Warning: Unknown output format: $output")
        }
    }
}

// ---------------------------------------------------------------------------
// analyze
// ---------------------------------------------------------------------------

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
        description = ["Output format: console, json:<path>, html:<path>. Can be specified multiple times."],
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
        println("JDBC Compliance Checker v0.3.0")
        println("Source: $sourcePath")
        println()

        val report = runAnalysis(sourcePath, driverName, entryClasses, specDir) ?: return 1
        println()
        dispatchOutputs(outputs, report)
        return 0
    }
}

// ---------------------------------------------------------------------------
// diff
// ---------------------------------------------------------------------------

@Command(
    name = "diff",
    description = [
        "Compare a baseline analysis JSON against the current driver source (or another JSON).",
        "",
        "Examples:",
        "  jdbc-checker diff baseline.json ./src/jdbc",
        "  jdbc-checker diff baseline.json current.json",
    ],
    mixinStandardHelpOptions = true,
)
class DiffCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["Path to the baseline analysis JSON report."],
    )
    lateinit var baselineJson: Path

    @Parameters(
        index = "1",
        description = [
            "Current source directory to analyze, or a second JSON report for a pure JSON diff.",
        ],
    )
    lateinit var current: Path

    @Option(
        names = ["-o", "--output"],
        description = ["Output format: console, json:<path>. Can be specified multiple times."],
    )
    var outputs: List<String> = listOf("console")

    @Option(
        names = ["-n", "--driver-name"],
        description = ["Driver name override for the current analysis."],
    )
    var driverName: String? = null

    @Option(
        names = ["-s", "--spec-dir"],
        description = ["Path to external JDBC spec YAML directory."],
    )
    var specDir: Path? = null

    override fun call(): Int {
        println("JDBC Compliance Checker — Diff")
        println()

        // Load baseline
        if (!Files.isRegularFile(baselineJson)) {
            System.err.println("Error: Baseline JSON not found: $baselineJson")
            return 1
        }
        print("Loading baseline report... ")
        val baseline = try {
            JsonReporter().loadReport(baselineJson)
        } catch (e: Exception) {
            System.err.println("Error reading baseline JSON: ${e.message}")
            return 1
        }
        println("${baseline.driverName}  (${baseline.analyzedAt.toString().take(10)})")

        // Load or analyze current
        val currentReport: AnalysisReport = if (current.toString().endsWith(".json")) {
            if (!Files.isRegularFile(current)) {
                System.err.println("Error: Current JSON not found: $current")
                return 1
            }
            print("Loading current report... ")
            val r = try {
                JsonReporter().loadReport(current)
            } catch (e: Exception) {
                System.err.println("Error reading current JSON: ${e.message}")
                return 1
            }
            println("${r.driverName}  (${r.analyzedAt.toString().take(10)})")
            r
        } else {
            println("Analyzing current source: $current")
            println()
            runAnalysis(current, driverName, specDir = specDir) ?: return 1
        }

        val diff = computeDiff(baseline, currentReport)

        println()
        for (output in outputs) {
            when {
                output == "console" -> DiffReporter().report(diff)
                output.startsWith("json:") -> {
                    val path = Path.of(output.removePrefix("json:"))
                    DiffJsonReporter().reportDiff(diff, path)
                }
                else -> System.err.println("Warning: Unknown output format: $output")
            }
        }

        return 0
    }
}

// ---------------------------------------------------------------------------
// compare
// ---------------------------------------------------------------------------

@Command(
    name = "compare",
    description = [
        "Compare two or more JDBC driver sources side by side.",
        "",
        "Examples:",
        "  jdbc-checker compare ./cubrid/src ./pgsql/src",
        "  jdbc-checker compare ./cubrid/src ./pgsql/src -n CUBRID -n PostgreSQL",
    ],
    mixinStandardHelpOptions = true,
)
class CompareCommand : Callable<Int> {

    @Parameters(
        index = "0..*",
        description = ["Two or more source paths (or JSON report files) to compare."],
        arity = "2..*",
    )
    lateinit var sources: List<Path>

    @Option(
        names = ["-n", "--driver-name"],
        description = ["Driver names (one per source, in order). Auto-detected if omitted."],
    )
    var driverNames: List<String> = emptyList()

    @Option(
        names = ["-o", "--output"],
        description = ["Output format: console, json:<path>. Can be specified multiple times."],
    )
    var outputs: List<String> = listOf("console")

    @Option(
        names = ["-s", "--spec-dir"],
        description = ["Path to external JDBC spec YAML directory."],
    )
    var specDir: Path? = null

    override fun call(): Int {
        println("JDBC Compliance Checker — Compare")
        println()

        val reports = sources.mapIndexed { idx, source ->
            val nameOverride = driverNames.getOrNull(idx)

            if (source.toString().endsWith(".json")) {
                // Load from JSON
                if (!Files.isRegularFile(source)) {
                    System.err.println("Error: Report JSON not found: $source")
                    return 1
                }
                print("Loading report ${idx + 1}: $source ... ")
                val r = try {
                    JsonReporter().loadReport(source)
                } catch (e: Exception) {
                    System.err.println("Error reading JSON: ${e.message}")
                    return 1
                }
                val r2 = if (nameOverride != null) r.copy(driverName = nameOverride) else r
                println(r2.driverName)
                r2
            } else {
                // Analyze source directory
                println("Analyzing source ${idx + 1}: $source")
                println()
                runAnalysis(source, nameOverride, specDir = specDir) ?: return 1
            }
        }

        val comparison = computeComparison(reports)

        println()
        for (output in outputs) {
            when {
                output == "console" -> ComparisonReporter().report(comparison)
                output.startsWith("json:") -> {
                    val path = Path.of(output.removePrefix("json:"))
                    DiffJsonReporter().reportComparison(comparison, path)
                }
                else -> System.err.println("Warning: Unknown output format: $output")
            }
        }

        return 0
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(JdbcCheckerCommand()).execute(*args)
    System.exit(exitCode)
}
