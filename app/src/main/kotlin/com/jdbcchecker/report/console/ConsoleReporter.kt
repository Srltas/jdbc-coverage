package com.jdbcchecker.report.console

import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.InterfaceResult
import com.jdbcchecker.model.Level1Status
import com.jdbcchecker.model.ImplementationStatus.Companion.toLevel1

/**
 * Generates human-readable console output for analysis results.
 */
class ConsoleReporter {

    fun report(result: AnalysisReport) {
        printHeader(result)
        printOverallSummary(result)
        printInterfaceDetails(result)
        printFooter(result)
    }

    private fun printHeader(result: AnalysisReport) {
        val separator = "=".repeat(70)
        println()
        println(separator)
        println("  JDBC Compliance Report — ${result.driverName}")
        println("  Source: ${result.sourcePath}")
        println("  Analyzed: ${result.analyzedAt}")
        println(separator)
    }

    private fun printOverallSummary(result: AnalysisReport) {
        println()
        println("  Overall Coverage: ${"%.1f".format(result.overallCoveragePercent)}%")
        println("  ${progressBar(result.overallCoveragePercent, 40)}")
        println()
        println("  Total: ${result.totalMethods} methods")
        println("    Implemented: ${result.totalImplemented}")
        println("    Stub:        ${result.totalStub}")
        println("    Not Found:   ${result.totalNotFound}")
        println()
    }

    private fun printInterfaceDetails(result: AnalysisReport) {
        val separator = "-".repeat(70)
        println(separator)
        println("  %-35s %8s %6s %6s %6s".format("Interface", "Coverage", "Impl", "Stub", "N/A"))
        println(separator)

        for (iface in result.interfaces.sortedByDescending { it.coveragePercent }) {
            val simpleName = iface.interfaceName.substringAfterLast('.')
            val implClass = iface.implementingClass?.substringAfterLast('.') ?: "—"
            println(
                "  %-35s %7.1f%% %6d %6d %6d".format(
                    "$simpleName ($implClass)",
                    iface.coveragePercent,
                    iface.implemented,
                    iface.stub,
                    iface.notFound,
                )
            )
        }
        println(separator)
    }

    private fun printFooter(result: AnalysisReport) {
        // Show top missing methods
        val missing = result.interfaces
            .flatMap { it.methods }
            .filter { it.status is ImplementationStatus.NotFound }
            .take(10)

        if (missing.isNotEmpty()) {
            println()
            println("  Top Missing Methods:")
            missing.forEach { method ->
                println("    - ${method.specMethod.displayName} (JDBC ${method.specMethod.jdbcVersion.display})")
            }
        }

        println()
    }

    private fun progressBar(percent: Double, width: Int): String {
        val filled = (percent / 100.0 * width).toInt().coerceIn(0, width)
        val empty = width - filled
        return "  [${"█".repeat(filled)}${"░".repeat(empty)}] ${"%.1f".format(percent)}%"
    }
}
