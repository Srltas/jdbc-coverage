package com.jdbcchecker.report.console

import com.jdbcchecker.model.DriverComparisonReport

/**
 * Renders a [DriverComparisonReport] to the console in a human-readable format.
 */
class ComparisonReporter {

    fun report(comparison: DriverComparisonReport) {
        printHeader(comparison)
        printCoverageSummary(comparison)
        printInterfaceTable(comparison)
        if (comparison.drivers.size == 2) {
            printMethodMatrix(comparison)
        }
    }

    private fun printHeader(comparison: DriverComparisonReport) {
        val sep = "=".repeat(70)
        println()
        println(sep)
        println("  JDBC Driver Comparison")
        comparison.drivers.forEachIndexed { i, d ->
            println("  Driver ${i + 1}: ${d.driverName}  (${"%.1f".format(d.coveragePercent)}%)")
        }
        println(sep)
    }

    private fun printCoverageSummary(comparison: DriverComparisonReport) {
        println()
        println("  Overall Coverage:")
        comparison.drivers.forEach { d ->
            val bar = progressBar(d.coveragePercent, 30)
            println("    ${"%-20s".format(d.driverName)}  $bar")
        }
        println()
    }

    private fun printInterfaceTable(comparison: DriverComparisonReport) {
        val sep = "-".repeat(70)
        val driverNames = comparison.drivers.map { it.driverName }

        println(sep)
        // Header: truncate driver names to 10 chars for column alignment
        val header = "  %-28s".format("Interface") +
            driverNames.joinToString("  ") { "%9s".format(it.take(9)) }
        println(header)
        println(sep)

        val allInterfaces = comparison.methodRows.map { it.interfaceName }.distinct().sorted()
        for (ifaceFqn in allInterfaces) {
            val simpleName = ifaceFqn.substringAfterLast('.')
            val row = "  %-28s".format(simpleName) +
                driverNames.joinToString("  ") { driverName ->
                    val coverage = comparison.drivers
                        .find { it.driverName == driverName }
                        ?.interfaceCoverages?.get(simpleName) ?: 0.0
                    "%8.1f%%".format(coverage)
                }
            println(row)
        }
        println(sep)
    }

    private fun printMethodMatrix(comparison: DriverComparisonReport) {
        val driverNames = comparison.drivers.map { it.driverName }
        val d1 = driverNames[0]
        val d2 = driverNames[1]

        val rows = comparison.methodRows
        val inBoth = rows.count { it.statuses[d1]!!.isImplemented() && it.statuses[d2]!!.isImplemented() }
        val onlyInD1 = rows.filter { it.statuses[d1]!!.isImplemented() && !it.statuses[d2]!!.isImplemented() }
        val onlyInD2 = rows.filter { !it.statuses[d1]!!.isImplemented() && it.statuses[d2]!!.isImplemented() }
        val inNeither = rows.count { !it.statuses[d1]!!.isImplemented() && !it.statuses[d2]!!.isImplemented() }
        val total = rows.size

        println()
        println("  Method Implementation Matrix  (total: $total):")
        println("    In both              : $inBoth")
        println("    Only in %-12s : ${onlyInD1.size}".format(d1.take(12)))
        println("    Only in %-12s : ${onlyInD2.size}".format(d2.take(12)))
        println("    In neither           : $inNeither")

        if (onlyInD1.isNotEmpty()) {
            println()
            println("  Methods only in $d1 (top 10):")
            onlyInD1.take(10).forEach { row ->
                println("    + ${row.methodDisplayName}  (JDBC ${row.jdbcVersion.display})")
            }
        }

        if (onlyInD2.isNotEmpty()) {
            println()
            println("  Methods only in $d2 (top 10):")
            onlyInD2.take(10).forEach { row ->
                println("    + ${row.methodDisplayName}  (JDBC ${row.jdbcVersion.display})")
            }
        }

        println()
    }

    private fun progressBar(percent: Double, width: Int): String {
        val filled = (percent / 100.0 * width).toInt().coerceIn(0, width)
        val empty = width - filled
        return "[${"\u2588".repeat(filled)}${"\u2591".repeat(empty)}] ${"%.1f".format(percent)}%"
    }
}
