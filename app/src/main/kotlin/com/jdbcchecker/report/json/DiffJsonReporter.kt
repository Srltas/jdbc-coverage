package com.jdbcchecker.report.json

import com.jdbcchecker.model.DiffReport
import com.jdbcchecker.model.DriverComparisonReport
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Generates JSON output for diff and comparison reports.
 */
class DiffJsonReporter {

    private val mapper = createObjectMapper()

    /**
     * Write a [DiffReport] to a JSON file.
     */
    fun reportDiff(diff: DiffReport, outputPath: Path) {
        outputPath.writeText(mapper.writeValueAsString(diff))
        println("Diff JSON report written to: $outputPath")
    }

    /**
     * Write a [DriverComparisonReport] to a JSON file.
     */
    fun reportComparison(comparison: DriverComparisonReport, outputPath: Path) {
        outputPath.writeText(mapper.writeValueAsString(comparison))
        println("Comparison JSON report written to: $outputPath")
    }
}
