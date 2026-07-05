package com.jdbcchecker.cli

import com.jdbcchecker.detector.ImplementationDetector
import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.InterfaceResult
import com.jdbcchecker.model.MethodResult
import com.jdbcchecker.parser.SourceParser
import com.jdbcchecker.profile.DriverProfile
import com.jdbcchecker.profile.ProfileResolver
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

/** Single source of truth for the tool version (shown in --version and stamped into reports). */
const val TOOL_VERSION = "2.0.0"

@Command(
    name = "jdbc-coverage",
    mixinStandardHelpOptions = true,
    version = ["jdbc-coverage $TOOL_VERSION"],
    description = ["Measure how much of the JDBC API a driver's source code implements."],
    subcommands = [
        AnalyzeCommand::class,
        DashboardCommand::class,
    ],
)
class JdbcCheckerCommand : Runnable {
    override fun run() {
        CommandLine.usage(this, System.out)
    }
}

// ---------------------------------------------------------------------------
// Analysis pipeline
// ---------------------------------------------------------------------------

/**
 * Runs the full analysis pipeline over one or more package-root source directories.
 *
 * @param sourcePaths package roots (e.g. driver/src/main/java); each must be a directory
 * @param driverName report display name (auto-detected from the first path if null)
 * @param entryClasses explicit "iface=class" pins from the CLI (win over profile pins)
 * @param profileName bundled profile name; null = auto-detect by package prefix
 * @return [AnalysisReport] or null if the pipeline fails
 */
internal fun runAnalysis(
    sourcePaths: List<Path>,
    driverName: String?,
    entryClasses: List<String> = emptyList(),
    profileName: String? = null,
    maxVersion: com.jdbcchecker.model.JdbcVersion? = null,
): AnalysisReport? {
    val invalidPaths = sourcePaths.filter { !Files.isDirectory(it) }
    if (invalidPaths.isNotEmpty()) {
        invalidPaths.forEach {
            System.err.println("Error: Source path does not exist or is not a directory: $it")
        }
        return null
    }

    // Step 1: Load the frozen JDBC spec
    print("Loading JDBC specification... ")
    val allSpecMethods = JdbcSpecLoader().loadAll()
    val specMethods = if (maxVersion == null) {
        allSpecMethods
    } else {
        allSpecMethods.filter { it.jdbcVersion.ordinal <= maxVersion.ordinal }
    }
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
    warnIfNotPackageRoot(sourcePaths, compilationUnits)

    // Step 2.5: Resolve driver profile (auto-detect unless --profile given)
    val profile: DriverProfile? = try {
        ProfileResolver().resolve(compilationUnits, profileName)
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}")
        return null
    }
    println(
        when {
            profile != null && profileName != null ->
                "Profile: ${profile.name} (${profile.displayName}, explicit)"
            profile != null -> "Profile: ${profile.name} (${profile.displayName}, auto-detected)"
            else -> "Profile: none (generic analyzer)"
        },
    )

    // Step 3: Resolve JDBC interface implementors.
    // --entry-class supports only the explicit "iface=class" form (validated in AnalyzeCommand);
    // CLI pins win over profile pins for one-off analyses.
    val explicitOverrides = entryClasses.associate { spec ->
        val eq = spec.indexOf('=')
        spec.substring(0, eq).trim() to spec.substring(eq + 1).trim()
    }
    val allOverrides = (profile?.entryClasses ?: emptyMap()) + explicitOverrides

    print("Resolving JDBC interface implementations... ")
    val implementors = try {
        JdbcInterfaceResolver().resolve(compilationUnits, allOverrides)
    } catch (e: IllegalStateException) {
        println()
        System.err.println("Error: ${e.message}")
        return null
    }
    println("${implementors.size} interfaces matched")

    // Step 4: Detect implementation status per method
    print("Analyzing implementation status... ")
    val detector = ImplementationDetector(
        stubHelpers = profile?.stubHelpers ?: emptyList(),
        extraStubExceptionClasses = profile?.stubExceptionClasses ?: emptyList(),
    )
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
        driverName = driverName ?: detectDriverName(sourcePaths.first()),
        sourcePath = sourcePaths.joinToString(", ") { it.toAbsolutePath().toString() },
        analyzedAt = Instant.now(),
        interfaces = interfaceResults,
        profileUsed = profile?.name,
        specVersion = JdbcSpecLoader.SPEC_VERSION,
        toolVersion = TOOL_VERSION,
        sourceCommit = resolveSourceCommit(sourcePaths.first()),
    )
}

/**
 * The symbol solver resolves inheritance chains only when each source path is a
 * package root (directory layout matches package declarations). Warn when it
 * isn't — analysis still runs, but entry-class detection quality degrades,
 * which historically caused large coverage swings (MySQL 47.2% vs 71.7%).
 */
internal fun warnIfNotPackageRoot(
    sourcePaths: List<Path>,
    compilationUnits: List<com.github.javaparser.ast.CompilationUnit>,
) {
    for (root in sourcePaths) {
        val absRoot = root.toAbsolutePath().normalize()
        val sample = compilationUnits.firstOrNull { cu ->
            val file = cu.storage.map { it.path.toAbsolutePath().normalize() }.orElse(null)
            file != null && file.startsWith(absRoot) && cu.packageDeclaration.isPresent
        } ?: continue
        val file = sample.storage.get().path.toAbsolutePath().normalize()
        val expectedRelDir = sample.packageDeclaration.get().nameAsString.replace('.', '/')
        val actualRelDir = absRoot.relativize(file.parent).toString().replace('\\', '/')
        if (actualRelDir != expectedRelDir) {
            System.err.println(
                "Warning: $root is not a package root (found package " +
                    "'${sample.packageDeclaration.get().nameAsString}' under '$actualRelDir'). " +
                    "Inheritance resolution may degrade; prefer passing the package root (e.g. src/main/java).",
            )
        }
    }
}

/**
 * Detect a display name from the deepest path segment that names a known driver.
 * Falls back to the directory basename. In CI, pass -n explicitly.
 */
internal fun detectDriverName(source: Path): String {
    val segments = source.toAbsolutePath().normalize().map { it.toString().lowercase() }.reversed()
    for (segment in segments) {
        when {
            "cubrid" in segment -> return "CUBRID JDBC"
            "mysql" in segment -> return "MySQL Connector/J"
            "mariadb" in segment -> return "MariaDB Connector/J"
            "postgresql" in segment || "pgjdbc" in segment -> return "PostgreSQL JDBC"
            "mssql" in segment || "sqlserver" in segment -> return "Microsoft SQL Server JDBC"
        }
    }
    return source.toAbsolutePath().fileName?.toString() ?: "Unknown"
}

/**
 * Best-effort `git rev-parse HEAD` of the repository containing [dir].
 * Returns null when git is unavailable or [dir] is not inside a work tree —
 * provenance is desirable but must never fail an analysis.
 */
internal fun resolveSourceCommit(dir: Path): String? = try {
    val process = ProcessBuilder("git", "-C", dir.toAbsolutePath().toString(), "rev-parse", "HEAD")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) output else null
} catch (e: Exception) {
    null
}

/**
 * Validate -o specs BEFORE analysis runs, so a typo can't silently drop a
 * daily snapshot (previously unknown formats warned but exited 0).
 * Returns one error message per invalid spec; empty = all valid.
 */
internal fun validateOutputs(outputs: List<String>): List<String> =
    outputs.mapNotNull { output ->
        when {
            output == "console" -> null
            output.startsWith("json:") && output.removePrefix("json:").isNotBlank() -> null
            else -> "Unknown output format: $output (expected console or json:<path>)"
        }
    }

/** Dispatch report outputs (console / json:<path>). */
internal fun dispatchOutputs(outputs: List<String>, report: AnalysisReport) {
    for (output in outputs) {
        when {
            output == "console" -> ConsoleReporter().report(report)
            output.startsWith("json:") -> {
                val path = Path.of(output.removePrefix("json:"))
                JsonReporter().report(report, path)
            }
            else -> error("Unvalidated output format: $output")
        }
    }
}

// ---------------------------------------------------------------------------
// analyze
// ---------------------------------------------------------------------------

@Command(
    name = "analyze",
    description = ["Analyze a JDBC driver's source and report JDBC API implementation coverage."],
    mixinStandardHelpOptions = true,
)
class AnalyzeCommand : Callable<Int> {

    @Parameters(
        index = "0..*",
        arity = "1..*",
        description = ["One or more source directories (package roots, e.g. driver/src/main/java)."],
    )
    lateinit var sources: List<Path>

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
        description = [
            "Pin the implementing class for a JDBC interface (overrides auto-detection).",
            "Format: --entry-class java.sql.Connection=com.example.ConnectionImpl",
            "Can be specified multiple times.",
        ],
    )
    var entryClasses: List<String> = emptyList()

    @Option(
        names = ["--profile"],
        description = [
            "Driver profile name (mssql, mysql, pgjdbc, mariadb, cubrid). Overrides auto-detection.",
        ],
    )
    var profileName: String? = null

    @Option(
        names = ["--jdbc-version"],
        description = [
            "Only include spec methods introduced at or before this JDBC version (e.g. 4.2).",
            "Default: the full bundled spec (latest version).",
        ],
    )
    var jdbcVersion: String? = null

    @Option(
        names = ["--history"],
        description = [
            "History directory: appends a compact line to history/<driver>.jsonl",
            "(with deltas vs the previous run) and overwrites latest/<driver>.json.",
        ],
    )
    var historyDir: Path? = null

    override fun call(): Int {
        println("JDBC Coverage v$TOOL_VERSION")
        println("Source: ${sources.joinToString(", ")}")
        println()

        val badEntries = entryClasses.filter { '=' !in it }
        if (badEntries.isNotEmpty()) {
            badEntries.forEach {
                System.err.println("Error: --entry-class requires 'iface=class' format, got: $it")
            }
            return 1
        }

        val outputErrors = validateOutputs(outputs)
        if (outputErrors.isNotEmpty()) {
            outputErrors.forEach { System.err.println("Error: $it") }
            return 1
        }

        val maxVersion = jdbcVersion?.let { requested ->
            com.jdbcchecker.model.JdbcVersion.fromString(requested) ?: run {
                System.err.println(
                    "Error: Unknown JDBC version: $requested " +
                        "(known: ${com.jdbcchecker.model.JdbcVersion.entries.joinToString { it.display }})",
                )
                return 1
            }
        }

        if (historyDir != null && maxVersion != null) {
            System.err.println(
                "Error: --history records full-spec snapshots; --jdbc-version would change " +
                    "the denominator and silently distort the trend. Run them separately.",
            )
            return 1
        }

        val report = runAnalysis(
            sourcePaths = sources,
            driverName = driverName,
            entryClasses = entryClasses,
            profileName = profileName,
            maxVersion = maxVersion,
        ) ?: return 1
        println()
        dispatchOutputs(outputs, report)
        historyDir?.let { dir ->
            val entry = com.jdbcchecker.history.HistoryRecorder(dir).record(report)
            val slug = com.jdbcchecker.history.HistoryRecorder.slugOf(report.driverName)
            println("History recorded: ${dir.resolve("history").resolve("$slug.jsonl")} (${entry.changes.size} changes)")
        }
        return 0
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(JdbcCheckerCommand()).execute(*args)
    System.exit(exitCode)
}
