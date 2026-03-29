package com.jdbcchecker.cli

import com.jdbcchecker.detector.ImplementationDetector
import com.jdbcchecker.git.GitCloneService
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
    version = ["jdbc-compliance-checker 1.0.0"],
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
 * Runs the full analysis pipeline on one or more JDBC driver source directories.
 *
 * @param sourcePaths resolved local paths to the source roots (multiple for multi-module drivers)
 * @param driverName optional driver name override (auto-detected from [sourceDisplay] if null)
 * @param entryClasses optional manual class overrides for interface detection
 * @param specDir optional external spec YAML directory (uses bundled if null)
 * @param sourceDisplay original source string (URL or path) shown in the report
 * @return [AnalysisReport] or null if the pipeline fails
 */
internal fun runAnalysis(
    sourcePaths: List<Path>,
    driverName: String?,
    entryClasses: List<String> = emptyList(),
    specDir: Path? = null,
    sourceDisplay: String? = null,
): AnalysisReport? {
    val invalidPaths = sourcePaths.filter { !Files.isDirectory(it) }
    if (invalidPaths.isNotEmpty()) {
        invalidPaths.forEach {
            System.err.println("Error: Source path does not exist or is not a directory: $it")
        }
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
    val parser = SourceParser(sourcePaths)
    val compilationUnits = parser.parseAll()
    val parseStats = parser.getParseStats()
    println(parseStats.summary())
    if (parseStats.failedFiles > 0) {
        parseStats.failures.forEach { (file, msg) ->
            System.err.println("  Warning: Failed to parse $file: $msg")
        }
    }
    if (compilationUnits.isEmpty()) {
        System.err.println("Error: No Java source files found in ${sourcePaths.joinToString()}")
        return null
    }

    // Step 3: Resolve JDBC interface implementors
    // Parse entry class overrides.
    // Supported formats:
    //   "java.sql.Connection=com.mysql.cj.jdbc.ConnectionImpl"  → explicit: forces the mapping
    //   "com.mysql.cj.jdbc.StatementImpl"                       → hint: auto-detects JDBC interface
    val explicitOverrides = entryClasses
        .filter { '=' in it }
        .associate { spec ->
            val eq = spec.indexOf('=')
            spec.substring(0, eq).trim() to spec.substring(eq + 1).trim()
        }
    val hintClasses = entryClasses.filter { '=' !in it }

    // For hint-only entries (no '='), resolve by finding the class in the parsed sources
    // and detecting which JDBC interface(s) it implements transitively.
    // This works when the full inheritance chain is available in the parsed files.
    val hintOverrides = if (hintClasses.isNotEmpty()) {
        resolveHintOverrides(compilationUnits, hintClasses)
    } else {
        emptyMap()
    }

    val allOverrides = hintOverrides + explicitOverrides // explicit takes precedence

    print("Resolving JDBC interface implementations... ")
    val implementors = JdbcInterfaceResolver().resolve(compilationUnits, allOverrides)
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

    val displaySource = sourceDisplay
        ?: sourcePaths.joinToString(", ") { it.toAbsolutePath().toString() }
    return AnalysisReport(
        driverName = driverName ?: detectDriverName(displaySource),
        sourcePath = displaySource,
        analyzedAt = Instant.now(),
        interfaces = interfaceResults,
    )
}

/**
 * For hint-style entry classes (class FQN only, no `=`), find each class in the parsed
 * compilation units and detect which JDBC interface(s) it implements transitively.
 * Returns a map of JDBC interface FQN → class FQN for any successful resolutions.
 *
 * This works when the full inheritance chain is available in the parsed source files.
 * If resolution fails (e.g., parent class not in scope), use the explicit
 * `java.sql.Connection=com.mysql.cj.jdbc.ConnectionImpl` format instead.
 */
private fun resolveHintOverrides(
    compilationUnits: List<com.github.javaparser.ast.CompilationUnit>,
    hintClasses: List<String>,
): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val resolver = JdbcInterfaceResolver()
    for (fqcn in hintClasses) {
        val simpleName = fqcn.substringAfterLast('.')
        val packageName = fqcn.substringBeforeLast('.', "")
        val classDecl = compilationUnits
            .flatMap { it.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration::class.java) }
            .filter { !it.isInterface }
            .firstOrNull { decl ->
                decl.nameAsString == simpleName &&
                    decl.findCompilationUnit()
                        .flatMap { it.packageDeclaration }
                        .map { it.nameAsString }
                        .orElse("") == packageName
            }
        if (classDecl == null) {
            System.err.println("Warning: Entry class hint '$fqcn' not found in parsed sources")
            continue
        }
        // Use a temporary single-class resolve to detect which JDBC interface it maps to
        val detected = resolver.resolve(listOf(classDecl.findCompilationUnit().get()))
        for ((jdbcIface, _) in detected) {
            result[jdbcIface] = fqcn
        }
        if (detected.isEmpty()) {
            System.err.println(
                "Warning: Could not auto-detect JDBC interface for '$fqcn'. " +
                    "Use 'java.sql.Connection=$fqcn' format for explicit mapping.",
            )
        }
    }
    return result
}

/**
 * Detect driver name from a source string (local path or Git URL).
 */
internal fun detectDriverName(source: String): String {
    val hint = if (GitCloneService.isGitUrl(source)) {
        source.substringAfterLast('/').removeSuffix(".git")
    } else {
        Path.of(source).toAbsolutePath().fileName?.toString() ?: "Unknown"
    }
    return detectDriverNameFromHint(hint)
}

private fun detectDriverNameFromHint(hint: String): String = when {
    "cubrid" in hint.lowercase() -> "CUBRID JDBC"
    "mysql" in hint.lowercase() -> "MySQL Connector/J"
    "mariadb" in hint.lowercase() -> "MariaDB Connector/J"
    "postgresql" in hint.lowercase() || "pgjdbc" in hint.lowercase() -> "PostgreSQL JDBC"
    else -> hint
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
        description = ["Local source path or Git repository URL."],
    )
    lateinit var source: String

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
        description = [
            "Manually specify implementing class (overrides auto-detection). Can be specified multiple times.",
            "Two formats supported:",
            "  ClassName only:  --entry-class com.mysql.cj.jdbc.StatementImpl",
            "    → auto-detects the JDBC interface (works if inheritance chain is fully parseable)",
            "  Explicit:        --entry-class java.sql.Connection=com.mysql.cj.jdbc.ConnectionImpl",
            "    → forces the mapping regardless of inheritance chain (use for transitive implementors)",
        ],
    )
    var entryClasses: List<String> = emptyList()

    @Option(
        names = ["-s", "--spec-dir"],
        description = ["Path to external JDBC spec YAML directory (uses bundled specs if not specified)."],
    )
    var specDir: Path? = null

    @Option(
        names = ["-b", "--branch"],
        description = ["Git branch or tag to clone (default: default branch). Only used with Git URLs."],
    )
    var branch: String? = null

    @Option(
        names = ["--source-subdir"],
        description = [
            "Subdirectory within the repository containing JDBC source (e.g., src/main/java).",
            "Can be specified multiple times for multi-module drivers.",
        ],
    )
    var sourceSubdirs: List<String> = emptyList()

    override fun call(): Int {
        println("JDBC Compliance Checker v1.0.0")
        println("Source: $source")
        println()

        SourceResolver().use { resolver ->
            val sourcePaths = resolver.resolve(source, branch, sourceSubdirs)
            val resolvedName = driverName ?: detectDriverName(source)
            val report = runAnalysis(sourcePaths, resolvedName, entryClasses, specDir, source) ?: return 1
            println()
            dispatchOutputs(outputs, report)
        }
        return 0
    }
}

// ---------------------------------------------------------------------------
// diff
// ---------------------------------------------------------------------------

@Command(
    name = "diff",
    description = [
        "Compare baseline JSON against current source or JSON.",
        "",
        "Examples:",
        "  jdbc-checker diff baseline.json ./src/jdbc",
        "  jdbc-checker diff baseline.json current.json",
        "  jdbc-checker diff baseline.json https://github.com/owner/jdbc-driver.git",
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
            "Current source path, Git URL, or JSON report for comparison.",
        ],
    )
    lateinit var current: String

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

    @Option(
        names = ["-b", "--branch"],
        description = ["Git branch or tag to clone (only used with Git URLs)."],
    )
    var branch: String? = null

    @Option(
        names = ["--source-subdir"],
        description = [
            "Subdirectory within the repository containing JDBC source.",
            "Can be specified multiple times for multi-module drivers.",
        ],
    )
    var sourceSubdirs: List<String> = emptyList()

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
        val currentReport: AnalysisReport
        if (current.endsWith(".json") && !GitCloneService.isGitUrl(current)) {
            val jsonPath = Path.of(current)
            if (!Files.isRegularFile(jsonPath)) {
                System.err.println("Error: Current JSON not found: $current")
                return 1
            }
            print("Loading current report... ")
            currentReport = try {
                JsonReporter().loadReport(jsonPath)
            } catch (e: Exception) {
                System.err.println("Error reading current JSON: ${e.message}")
                return 1
            }
            println("${currentReport.driverName}  (${currentReport.analyzedAt.toString().take(10)})")
        } else {
            println("Analyzing current source: $current")
            println()
            SourceResolver().use { resolver ->
                val sourcePaths = resolver.resolve(current, branch, sourceSubdirs)
                val resolvedName = driverName ?: detectDriverName(current)
                val report = runAnalysis(sourcePaths, resolvedName, specDir = specDir, sourceDisplay = current)
                if (report == null) return 1
                currentReport = report
            }
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
        "  jdbc-checker compare cubrid.json https://github.com/owner/pgjdbc.git",
    ],
    mixinStandardHelpOptions = true,
)
class CompareCommand : Callable<Int> {

    @Parameters(
        index = "0..*",
        description = ["Two or more source paths, Git URLs, or JSON report files to compare."],
        arity = "2..*",
    )
    lateinit var sources: List<String>

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

    @Option(
        names = ["-b", "--branch"],
        description = ["Git branch or tag to clone (applies to all Git URL sources)."],
    )
    var branch: String? = null

    @Option(
        names = ["--source-subdir"],
        description = [
            "Subdirectory within repositories containing JDBC source (applies to all sources).",
            "Can be specified multiple times for multi-module drivers.",
        ],
    )
    var sourceSubdirs: List<String> = emptyList()

    override fun call(): Int {
        println("JDBC Compliance Checker — Compare")
        println()

        SourceResolver().use { resolver ->
            val reports = sources.mapIndexed { idx, source ->
                val nameOverride = driverNames.getOrNull(idx)

                if (source.endsWith(".json") && !GitCloneService.isGitUrl(source)) {
                    // Load from JSON
                    val jsonPath = Path.of(source)
                    if (!Files.isRegularFile(jsonPath)) {
                        System.err.println("Error: Report JSON not found: $source")
                        return 1
                    }
                    print("Loading report ${idx + 1}: $source ... ")
                    val r = try {
                        JsonReporter().loadReport(jsonPath)
                    } catch (e: Exception) {
                        System.err.println("Error reading JSON: ${e.message}")
                        return 1
                    }
                    val r2 = if (nameOverride != null) r.copy(driverName = nameOverride) else r
                    println(r2.driverName)
                    r2
                } else {
                    // Resolve (local path or Git URL) and analyze
                    println("Analyzing source ${idx + 1}: $source")
                    println()
                    val sourcePaths = resolver.resolve(source, branch, sourceSubdirs)
                    val resolvedName = nameOverride ?: detectDriverName(source)
                    runAnalysis(sourcePaths, resolvedName, specDir = specDir, sourceDisplay = source)
                        ?: return 1
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
        }

        return 0
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(JdbcCheckerCommand()).execute(*args)
    System.exit(exitCode)
}
