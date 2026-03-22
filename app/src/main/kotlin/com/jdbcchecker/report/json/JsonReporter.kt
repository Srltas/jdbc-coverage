package com.jdbcchecker.report.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jdbcchecker.model.AnalysisReport
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Generates JSON output for CI integration and data analysis.
 */
class JsonReporter {

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * Write analysis report to a JSON file.
     */
    fun report(result: AnalysisReport, outputPath: Path) {
        val json = mapper.writeValueAsString(result)
        outputPath.writeText(json)
        println("JSON report written to: $outputPath")
    }

    /**
     * Serialize analysis report to JSON string.
     */
    fun toJson(result: AnalysisReport): String =
        mapper.writeValueAsString(result)
}
