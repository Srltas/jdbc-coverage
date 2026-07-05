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
        printVersionBreakdown(result)
        printCumulativeCoverage(result)
        printInterfaceDetails(result)
        printStatusDistribution(result)
        printFooter(result)
    }

    private fun printHeader(result: AnalysisReport) {
        val separator = "=".repeat(70)
        println()
        println(separator)
        println("  JDBC Compliance Report — ${result.driverName}")
        println("  Source: ${result.sourcePath}")
        println("  Analyzed: ${result.analyzedAt}")
        result.profileUsed?.let { println("  Profile: $it") }
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

    private fun printVersionBreakdown(result: AnalysisReport) {
        val separator = "-".repeat(70)
        println(separator)
        println("  JDBC Version Breakdown:")
        println(separator)

        for ((version, coverage) in result.versionBreakdown) {
            val bar = progressBar(coverage.coveragePercent, 20)
            println(
                "  JDBC %-4s  %s  %3d/%3d".format(
                    version.display,
                    bar,
                    coverage.implemented,
                    coverage.total,
                ),
            )
        }
        println()
    }

    private fun printCumulativeCoverage(result: AnalysisReport) {
        val separator = "-".repeat(70)
        println(separator)
        println("  Cumulative Coverage (all methods introduced at or before version):")
        println(separator)

        for (c in result.cumulativeCoverage) {
            val bar = progressBar(c.coveragePercent, 20)
            println("  <=%-4s  %s  %4d/%4d".format(c.version.display, bar, c.implemented, c.total))
        }
        println()
    }

    private fun printStatusDistribution(result: AnalysisReport) {
        val separator = "-".repeat(70)
        println(separator)
        println("  Implementation Detail (Level 2):")
        println(separator)

        for ((label, count) in result.statusDistribution.entries.sortedByDescending { it.value }) {
            val percent = count.toDouble() / result.totalMethods * 100.0
            println("    %-25s %4d  (%4.1f%%)".format(label, count, percent))
        }
        println()
    }

    private fun printInterfaceDetails(result: AnalysisReport) {
        val colWidth = 35
        val separator = "-".repeat(70)
        println(separator)
        println("  %-${colWidth}s %8s %6s %6s %6s".format("Interface", "Coverage", "Impl", "Stub", "N/A"))
        println(separator)

        for (iface in result.interfaces.sortedByDescending { it.coveragePercent }) {
            val simpleName = iface.interfaceName.substringAfterLast('.')
            val implClass = iface.implementingClass?.substringAfterLast('.') ?: "—"
            val displayName = truncateInterfaceLabel(simpleName, implClass, colWidth)
            println(
                "  %-${colWidth}s %7.1f%% %6d %6d %6d".format(
                    displayName,
                    iface.coveragePercent,
                    iface.implemented,
                    iface.stub,
                    iface.notFound,
                )
            )
        }
        println(separator)
    }

    /**
     * Builds a display label like "Interface (ImplClass)" that fits within [maxLen].
     *
     * The interface name is always preserved in full. If the combined label exceeds
     * [maxLen], the implementation class name is truncated with "..." suffix.
     * When there is no implementing class, only the interface name is shown.
     */
    private fun truncateInterfaceLabel(interfaceName: String, implClass: String, maxLen: Int): String {
        if (implClass == "—") return "$interfaceName ($implClass)"

        val full = "$interfaceName ($implClass)"
        if (full.length <= maxLen) return full

        // "Interface (" = interfaceName.length + 2, trailing "...)" = 4
        val overhead = interfaceName.length + 2 + 4 // " (" + "...)"
        val available = maxLen - overhead
        return if (available > 0) {
            "$interfaceName (${implClass.take(available)}...)"
        } else {
            // Extreme case: even interface name + overhead exceeds maxLen
            interfaceName
        }
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
