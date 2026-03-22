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
import kotlin.streams.asSequence

/**
 * Parses Java source files using JavaParser with Symbol Solver configured.
 *
 * Symbol Solver enables resolution of:
 * - Fully qualified type names
 * - Inheritance chains (extends/implements)
 * - Method overriding relationships
 */
class SourceParser(private val sourcePaths: List<Path>) {

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
     */
    fun parseFile(file: Path): CompilationUnit? =
        try {
            StaticJavaParser.parse(file)
        } catch (e: Exception) {
            System.err.println("Warning: Failed to parse ${file}: ${e.message}")
            null
        }
}
