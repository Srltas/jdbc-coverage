package com.jdbcchecker.report.console

import com.jdbcchecker.model.DiffReport
import com.jdbcchecker.model.ImplementationStatus

/**
 * Renders a [DiffReport] to the console in a human-readable format.
 */
class DiffReporter {

    fun report(diff: DiffReport) {
        printHeader(diff)
        printSummary(diff)
        printImproved(diff)
        printRegressed(diff)
        printStillMissing(diff)
    }

    private fun printHeader(diff: DiffReport) {
        val sep = "=".repeat(70)
        println()
        println(sep)
        println("  JDBC Compliance Diff")
        println(
            "  Baseline : ${diff.baselineDriverName}" +
                "  (${diff.baselineAnalyzedAt.toString().take(10)})",
        )
        println(
            "  Current  : ${diff.currentDriverName}" +
                "  (${diff.currentAnalyzedAt.toString().take(10)})",
        )
        println(sep)
    }

    private fun printSummary(diff: DiffReport) {
        val deltaSign = if (diff.coverageDelta >= 0) "+" else ""
        println()
        println(
            "  Coverage : ${"%.1f".format(diff.baselineCoverage)}%  →  " +
                "${"%.1f".format(diff.currentCoverage)}%  " +
                "($deltaSign${"%.1f".format(diff.coverageDelta)}%)",
        )
        println()
        println("  Improved  : ${diff.improved.size} methods")
        println("  Regressed : ${diff.regressed.size} methods")
        println("  Unchanged : ${diff.unchanged.size} methods")
        println()
    }

    private fun printImproved(diff: DiffReport) {
        if (diff.improved.isEmpty()) return
        val sep = "-".repeat(70)
        println(sep)
        println("  Improved (${diff.improved.size} methods):")
        println(sep)
        diff.improved.forEach { m ->
            println("  + ${m.methodDisplayName}  (JDBC ${m.jdbcVersion.display})")
            println("      ${m.before.label}  →  ${m.after.label}")
        }
        println()
    }

    private fun printRegressed(diff: DiffReport) {
        if (diff.regressed.isEmpty()) return
        val sep = "-".repeat(70)
        println(sep)
        println("  Regressed (${diff.regressed.size} methods):")
        println(sep)
        diff.regressed.forEach { m ->
            println("  - ${m.methodDisplayName}  (JDBC ${m.jdbcVersion.display})")
            println("      ${m.before.label}  →  ${m.after.label}")
        }
        println()
    }

    private fun printStillMissing(diff: DiffReport) {
        val stillMissing = diff.unchanged.filter { it.after is ImplementationStatus.NotFound }
        if (stillMissing.isEmpty()) return
        val sep = "-".repeat(70)
        println(sep)
        println("  Still Not Implemented (${stillMissing.size} total — showing top 20):")
        println(sep)
        stillMissing.take(20).forEach { m ->
            println("    ${m.methodDisplayName}  (JDBC ${m.jdbcVersion.display})")
        }
        println()
    }
}
