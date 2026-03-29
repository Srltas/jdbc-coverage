package com.jdbcchecker.parser

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Parse statistics collected during a [SourceParser.parseAll] run.
 *
 * @param totalFiles    number of .java files found across all source paths
 * @param parsedFiles   number of files successfully converted to CompilationUnits
 * @param failedFiles   number of files that failed to parse
 * @param failures      list of (file path, error message) for failed files
 */
data class ParseStats(
    val totalFiles: Int,
    val parsedFiles: Int,
    val failedFiles: Int,
    val failures: List<Pair<Path, String>>,
) {
    val successRate: Double
        get() = if (totalFiles > 0) parsedFiles.toDouble() / totalFiles * 100 else 100.0

    /** Returns a one-line summary suitable for console output. */
    fun summary(): String {
        val base = "$parsedFiles / $totalFiles files parsed"
        return if (failedFiles == 0) base
        else "$base ($failedFiles failed — run with --verbose for details)"
    }
}

/**
 * Parses Java source files using JavaParser with Symbol Solver configured.
 *
 * Symbol Solver enables resolution of:
 * - Fully qualified type names
 * - Inheritance chains (extends/implements)
 * - Method overriding relationships
 *
 * Parse failures (syntax errors, unsupported Java features, encoding issues)
 * are silently skipped but tracked; call [getParseStats] after [parseAll] to
 * inspect the results.
 */
class SourceParser(private val sourcePaths: List<Path>) {

    private var totalFiles = 0
    private var parsedFiles = 0
    private val failures = mutableListOf<Pair<Path, String>>()

    init {
        configureSolver()
    }

    private fun configureSolver() {
        val typeSolver = CombinedTypeSolver().apply {
            // JDK types (java.sql.*, javax.sql.*, etc.)
            add(ReflectionTypeSolver())
            // Project source paths
            sourcePaths.forEach { path ->
                if (Files.isDirectory(path)) {
                    add(JavaParserTypeSolver(path))
                }
            }
        }

        val symbolSolver = JavaSymbolSolver(typeSolver)
        StaticJavaParser.getParserConfiguration().setSymbolResolver(symbolSolver)
    }

    /**
     * Parse all Java source files under the configured source paths.
     * After this call, use [getParseStats] to inspect success/failure counts.
     */
    fun parseAll(): List<CompilationUnit> =
        sourcePaths.flatMap { parseDirectory(it) }

    /**
     * Parse all Java files in a directory recursively.
     */
    fun parseDirectory(directory: Path): List<CompilationUnit> =
        Files.walk(directory)
            .asSequence()
            .filter { it.isRegularFile() && it.extension == "java" }
            .mapNotNull { parseFile(it) }
            .toList()

    /**
     * Parse a single Java source file.
     * Returns null on failure; the failure is recorded in internal stats.
     */
    fun parseFile(file: Path): CompilationUnit? {
        totalFiles++
        return try {
            val cu = StaticJavaParser.parse(file)
            parsedFiles++
            cu
        } catch (e: Exception) {
            failures.add(file to (e.message?.take(120) ?: "Unknown error"))
            null
        }
    }

    /**
     * Returns parse statistics for the most recent [parseAll] / [parseDirectory]
     * / [parseFile] invocations since this instance was created.
     */
    fun getParseStats(): ParseStats = ParseStats(
        totalFiles = totalFiles,
        parsedFiles = parsedFiles,
        failedFiles = failures.size,
        failures = failures.toList(),
    )
}
