package com.jdbccoverage.report.json

import com.jdbccoverage.model.AnalysisReport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Generates JSON output for CI integration and data analysis.
 */
class JsonReporter {

    private val mapper = createObjectMapper()

    /**
     * Write analysis report to a JSON file.
     */
    fun report(result: AnalysisReport, outputPath: Path) {
        outputPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        outputPath.writeText(mapper.writeValueAsString(result))
        println("JSON report written to: $outputPath")
    }

    /**
     * Serialize analysis report to JSON string.
     */
    fun toJson(result: AnalysisReport): String = mapper.writeValueAsString(result)

    /**
     * Load a previously saved [AnalysisReport] from a JSON file.
     */
    fun loadReport(jsonPath: Path): AnalysisReport =
        mapper.readValue(jsonPath.readText(), AnalysisReport::class.java)
}
